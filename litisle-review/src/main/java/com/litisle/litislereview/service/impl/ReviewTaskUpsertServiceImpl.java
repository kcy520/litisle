package com.litisle.litislereview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litislereview.domain.entity.ModerationRecord;
import com.litisle.litislereview.domain.entity.ReviewTask;
import com.litisle.litislereview.enums.Layer1HitEnum;
import com.litisle.litislereview.enums.Layer2RiskEnum;
import com.litisle.litislereview.enums.ModTypeEnum;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;
import com.litisle.litislereview.enums.ReviewTaskStatusEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import com.litisle.litislereview.mapper.ModerationRecordMapper;
import com.litisle.litislereview.mapper.ReviewTaskMapper;
import com.litisle.litislereview.service.ReviewTaskUpsertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 统一建任务 upsert 服务实现（板块三 3-3，§4.2.2）
 *
 * 【依赖】
 * - ReviewTaskMapper：按 uk_biz_scene 三元组定位 + upsert 写入
 * - ModerationRecordMapper：重置前追加 superseded 留痕行（append-only）
 *
 * 【并发兜底】并发 upsert 同时 insert 撞 uk_biz_scene 由唯一键约束兜底，
 * 捕获 DuplicateKeyException 后转复用分支重查返回既有任务ID
 *
 * 【superseded 留痕行的 mod_type/verdict 取值说明】
 * moderation_record.mod_type/verdict 均 NOT NULL 且枚举口径分别为 1-6 / 1-3，无「系统重置」专用值；
 * 暂复用 MANUAL_REVIEW(5) + SUSPICIOUS(3) 承载，reason 固定以 "superseded：" 前缀标识供统计过滤，
 * 非人工裁决语义；后续如扩展 mod_type=7-任务重置 再迁移（需同步 Sql-Document.md 列注释）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTaskUpsertServiceImpl implements ReviewTaskUpsertService {

    /** moderation_record.reason 列长度（落库前截断，防超长中断写入） */
    private static final int REASON_MAX_LENGTH = 500;

    /** superseded 留痕行 reason 固定前缀（统计过滤锚点） */
    private static final String SUPERSEDED_REASON_PREFIX = "superseded：任务重置标记（非裁决）";

    /** 审核任务表（uk_biz_scene 定位 + upsert 写入） */
    private final ReviewTaskMapper reviewTaskMapper;
    /** 审核记录表（重置前追加 superseded 留痕行） */
    private final ModerationRecordMapper moderationRecordMapper;

    /**
     * 按 uk_biz_scene(biz_type, biz_id, scene) 幂等 upsert 审核任务
     * <p>整体写入包同一本地事务：重置分支的 superseded 留痕 + 任务重置原子提交，任一失败整体回滚</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long upsert(ModerationBizTypeEnum bizType, Long bizId, ReviewSceneEnum scene,
                       String contentHash, Layer1HitEnum layer1Hit, String hitWords) {
        // 步骤1：参数闸门——USERNAME 同步 DFA 拒绝不建任务（唯一例外，只落 record 不建 task），
        //        其余必带参数缺失抛 PARAM_ERROR，不产生任何 DB 写入
        if (bizType == ModerationBizTypeEnum.USERNAME) {
            log.warn("统一建任务收到 USERNAME 请求，USERNAME 同步拒绝不建任务（唯一例外），bizId={}", bizId);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        if (bizType == null || bizId == null || bizId <= 0 || scene == null || !StringUtils.hasText(contentHash)) {
            log.error("统一建任务参数缺失，bizType={}，bizId={}，scene={}，contentHashEmpty={}",
                    bizType, bizId, scene, !StringUtils.hasText(contentHash));
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        // 步骤2：按 (biz_type, biz_id, scene) 三元组定位任务行（uk_biz_scene 保证至多一行）
        ReviewTask existing = reviewTaskMapper.selectOne(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getBizType, bizType)
                .eq(ReviewTask::getBizId, bizId)
                .eq(ReviewTask::getScene, scene));

        // 步骤3：无行 → insert：status 按场景初始值分列、content_hash、layer1_hit、hit_words、retry_count=0；
        //        并发 insert 撞 uk_biz_scene 由唯一键兜底，捕获后转复用分支重查
        if (existing == null) {
            try {
                return insertNewTask(bizType, bizId, scene, contentHash, layer1Hit, hitWords);
            } catch (DuplicateKeyException e) {
                // 并发窗口另一请求已建同键任务：重查后按复用分支返回其任务ID
                existing = reviewTaskMapper.selectOne(new LambdaQueryWrapper<ReviewTask>()
                        .eq(ReviewTask::getBizType, bizType)
                        .eq(ReviewTask::getBizId, bizId)
                        .eq(ReviewTask::getScene, scene));
                if (existing == null) {
                    throw e;
                }
                log.info("并发建任务撞 uk_biz_scene，转复用既有任务，taskId={}", existing.getId());
            }
        }

        // 步骤4：命中行分流（★核心）
        boolean hashChanged = !Objects.equals(existing.getContentHash(), contentHash);
        boolean terminal = isTerminal(existing.getStatus());
        if (!hashChanged && !terminal) {
            // b. hash 未变且非终态（status∈{0,2}）→ 只复用不重置：保留 status/assignee 不打断已认领在审任务，
            //    仅返回任务ID 由调用方回填关联列（如 report.review_task_id）
            log.info("任务复用不重置（hash 未变且非终态），taskId={}，status={}", existing.getId(), existing.getStatus());
            return existing.getId();
        }
        // a. content_hash 变化（编辑重发）或原任务已终态（status∈{1,3,4}）→ 重置分支
        if (!terminal) {
            // ① 旧任务未终态时先追加 superseded 留痕行（append-only，历史链路靠流水保留）
            insertSupersededRecord(existing);
        }
        // ② 重置：目标态按 scene 分列 + 清人审占据列 + 更新 content_hash + retry_count 归零
        //    + 清机审证据列（layer1Hit/hitWords 由调用方传入新内容 DFA 命中快照，无则传 null 即清空）
        ReviewTaskStatusEnum targetStatus = resolveStatusByScene(scene);
        int affected = reviewTaskMapper.update(null, new LambdaUpdateWrapper<ReviewTask>()
                .set(ReviewTask::getStatus, targetStatus)
                .set(ReviewTask::getContentHash, contentHash)
                .set(ReviewTask::getRetryCount, 0)
                .set(ReviewTask::getAssignee, null)
                .set(ReviewTask::getClaimTime, null)
                .set(ReviewTask::getDecidedBy, null)
                .set(ReviewTask::getOpinion, null)
                .set(ReviewTask::getDecideTime, null)
                .set(ReviewTask::getModVerdict, VerdictEnum.NONE)
                .set(ReviewTask::getLayer1Hit, layer1Hit == null ? Layer1HitEnum.NONE : layer1Hit)
                .set(ReviewTask::getHitWords, hitWords)
                .set(ReviewTask::getLayer2Risk, Layer2RiskEnum.NOT_SUBMITTED)
                .set(ReviewTask::getUpdateTime, LocalDateTime.now())
                .eq(ReviewTask::getId, existing.getId()));
        if (affected == 0) {
            // 防御性兜底：review_task 无物理删，更新落空理论不可达（并发状态机异常时显式失败宁严勿松）
            log.error("任务重置更新落空，taskId={}，targetStatus={}", existing.getId(), targetStatus);
            throw new IllegalStateException("任务重置更新落空，taskId=" + existing.getId());
        }
        log.info("任务重置完成，taskId={}，触发={}，目标态={}，新hash={}",
                existing.getId(), hashChanged ? "内容指纹变化" : "原任务已终态", targetStatus, contentHash);
        // 步骤6：返回任务ID（重置/复用均为既有行主键，供调用方回填关联列）
        return existing.getId();
    }

    /**
     * 新建任务行：status 按场景初始值分列（待机审场景=0-待审 / 举报复审场景=2-人工待审），返回主键即任务ID
     */
    private Long insertNewTask(ModerationBizTypeEnum bizType, Long bizId, ReviewSceneEnum scene,
                               String contentHash, Layer1HitEnum layer1Hit, String hitWords) {
        LocalDateTime now = LocalDateTime.now();
        ReviewTask task = new ReviewTask()
                .setBizType(bizType)
                .setBizId(bizId)
                .setScene(scene)
                .setContentHash(contentHash)
                .setStatus(resolveStatusByScene(scene))
                .setLayer1Hit(layer1Hit == null ? Layer1HitEnum.NONE : layer1Hit)
                .setHitWords(hitWords)
                .setLayer2Risk(Layer2RiskEnum.NOT_SUBMITTED)
                .setModVerdict(VerdictEnum.NONE)
                .setRetryCount(0)
                .setCreateTime(now)
                .setUpdateTime(now);
        reviewTaskMapper.insert(task);
        log.info("新建审核任务，taskId={}，bizType={}，bizId={}，scene={}，status={}",
                task.getId(), bizType, bizId, scene, task.getStatus());
        return task.getId();
    }

    /**
     * 按场景分列任务状态：举报复审场景直进人审（不重跑机审），其余待机审场景回到机审待处理
     */
    private ReviewTaskStatusEnum resolveStatusByScene(ReviewSceneEnum scene) {
        switch (scene) {
            case ARTICLE_RE_REVIEW:
            case COMMENT_RE_REVIEW:
            case MESSAGE_RE_REVIEW:
                // 举报复审场景：status=2-人工待审（直进人审，不重跑机审）
                return ReviewTaskStatusEnum.MANUAL_PENDING;
            default:
                // 待机审场景：ARTICLE_FEATURED/ARTICLE_COMMUNITY/COMMENT/MESSAGE/IMAGE/ARTICLE_IMG_RE_REVIEW
                // （ARTICLE_IMG_RE_REVIEW 虽带 _RE_REVIEW 后缀但属机审复审，逐图送 SPI，不得并入直进人审一类）
                return ReviewTaskStatusEnum.PENDING;
        }
    }

    /**
     * 判断任务是否已终态（status∈{1-自动通过，3-人审通过，4-人审驳回}）
     */
    private boolean isTerminal(ReviewTaskStatusEnum status) {
        return status == ReviewTaskStatusEnum.AUTO_PASS
                || status == ReviewTaskStatusEnum.PASS
                || status == ReviewTaskStatusEnum.REJECT;
    }

    /**
     * 追加 superseded 留痕行：重置前固化旧任务现场快照（append-only，历史链路靠流水保留）
     * <p>mod_type/verdict 受 NOT NULL + 枚举口径 1-6/1-3 约束，暂复用 MANUAL_REVIEW(5)/SUSPICIOUS(3) 承载，
     * reason 固定以 "superseded：" 前缀标识供统计过滤，非人工裁决语义（详见类 Javadoc 说明）</p>
     */
    private void insertSupersededRecord(ReviewTask oldTask) {
        // 固化旧任务现场：原状态/原指纹/原认领人/原命中词一并入 reason 与对应列，取证自包含免 JOIN
        String reason = SUPERSEDED_REASON_PREFIX
                + " — 原status=" + oldTask.getStatus()
                + "，原content_hash=" + oldTask.getContentHash()
                + "，原assignee=" + oldTask.getAssignee()
                + "，触发=内容指纹变化或原任务已终态，重置前固化旧任务现场";
        ModerationRecord record = new ModerationRecord()
                .setModType(ModTypeEnum.MANUAL_REVIEW)
                .setBizType(oldTask.getBizType())
                .setBizId(oldTask.getBizId())
                .setReviewTaskId(oldTask.getId())
                .setScene(oldTask.getScene())
                .setContentHash(oldTask.getContentHash())
                .setVerdict(VerdictEnum.SUSPICIOUS)
                .setHitWords(oldTask.getHitWords())
                .setReason(truncate(reason, REASON_MAX_LENGTH))
                .setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY))
                .setOperatorId(0L)
                .setOperatorName("system")
                .setCreateTime(LocalDateTime.now());
        moderationRecordMapper.insert(record);
        log.info("追加 superseded 留痕行，recordId={}，taskId={}，原status={}",
                record.getId(), oldTask.getId(), oldTask.getStatus());
    }

    /**
     * reason 截断保护：超长截断至 maxLength，防超长中断写入
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}