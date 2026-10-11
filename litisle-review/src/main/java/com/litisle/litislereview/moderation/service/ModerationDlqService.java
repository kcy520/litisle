package com.litisle.litislereview.moderation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litisleapi.event.moderation.ModerationResultEvent;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
import com.litisle.litislereview.domain.entity.ReviewTask;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;
import com.litisle.litislereview.enums.ReviewTaskStatusEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import com.litisle.litislereview.mapper.ReviewTaskMapper;
import com.litisle.litislereview.mq.publisher.ModerationResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * 机审死信转人审编排服务（板块三 3-6，§4.2.4——占位骨架：暂不实现，方法体只留步骤注释）
 *
 * 【职责】DLQ 死信的业务兑底（按场景分流，绝不自动通过）：
 * - 文本死信：定位任务 → 转人审三件套 CAS；
 * - 图片死信 scene=IMAGE：单图审不建 review_task（防任务爆炸，ModerationImageService 既有口径）——
 *   无任务可 CAS，直接发布 verdict=SUSPICIOUS(3) result 事件，由 file 侧置 safe_flag=3（存疑占位图）；
 * - 图片死信 scene=ARTICLE_IMG_RE_REVIEW：文章级复审任务存在（biz_type=1），走转人审三件套 CAS。
 *
 * 【转人审三件套口径（绝不自动通过）】
 * CAS：update review_task set status=2(人工待审), mod_verdict=3(SUSPICIOUS), retry_count=事件重试次数
 *      where id=? and status=0(待审)
 * - CAS 条件 status=0 与板块六 6-2 补偿重发（只扫 status=0）天然互斥，防死信与补偿双路并发处理
 * - affected=0 → 任务已在人审在办（status=2）/已终态（1/3/4）/已被补偿重发，info 留痕后 ack（残留竞态窗口靠 CAS 收敛）
 * - retry_count 取事件体 retryCount（3-8.1 消息头递增口径），与 review_task.retry_count 对账留痕
 *
 * 【图片 safe_flag=3 联动（仅 scene=IMAGE，biz_type=6）】发布 verdict=SUSPICIOUS(3) 的 ModerationResultEvent
 * （复用 3-4 {@link ModerationResultPublisher#publish}，路由 moderation.image.result），
 * file 侧既有 result 消费链路据此置 safe_flag=3（存疑占位图，四态闸口既有语义）；
 * 发布失败仅 error 留痕不抛（DLQ 无 DLX 兑底，抛异常会被 nack 后物理丢弃）——
 * safe_flag 停留 PENDING，default-deny 闸口不放行（宁严勿松）。
 * 【复审死信不联动 safe_flag】ARTICLE_IMG_RE_REVIEW（biz_type=1）配图 safe_flag 由单图审链路独立承载；
 * 其 result 消费端（content 侧，3-5）暂未落地，发布亦无人消费，故复审死信仅做任务 CAS 不发事件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModerationDlqService {

    /** 审核任务 Mapper（三元组定位 + 转人审 CAS） */
    private final ReviewTaskMapper reviewTaskMapper;

    /** 机审结果统一发布器（3-4 产物复用：IMAGE 死信发布 safe_flag=3 存疑事件） */
    private final ModerationResultPublisher moderationResultPublisher;

    /**
     * 文本死信转人审（bizType∈{1-文章,2-评论,3-用户名,4-作家申请,5-私信}）
     *
     * @param event 原文本送审事件（死信体）
     */
    public void processTextDeadLetter(TextSubmitEvent event) {
        // 步骤1（3-6.1）：合法性校验——bizType/bizId/scene 缺失 → error + BizException（消费端 ack 丢弃脏消息，重试无意义）
        if (event == null || event.getBizType() == null || event.getBizId() == null
                || event.getScene() == null || event.getScene().isBlank()) {
            log.error("文本死信缺少 bizType/bizId/scene，确认丢弃，event={}", event);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤2（3-6.1）：按 (biz_type,biz_id,scene) 定位 review_task（uk_biz_scene 唯一）；
        //        任务缺失 → error + BizException ack 丢弃（任务已被 upsert 演化/重置属正常终局）
        ModerationBizTypeEnum bizType = toBizType(event.getBizType());
        ReviewSceneEnum scene = parseScene(event.getScene());
        ReviewTask task = locateTask(bizType, event.getBizId(), scene, "文本死信");
        // 步骤2.1：聚合指纹一致性校验——任务已被 upsert 重置（hash 不一致）时，旧死信转人审会污染新内容任务，确认丢弃
        verifyHashMatch(task, event.getContentHash(), "文本死信");
        // 步骤3（3-6.2）：转人审三件套 CAS（口径见类注释）；affected=0 → info 留痕返回
        transitionToManualReview(task, event.getRetryCount(), "文本死信");
    }

    /**
     * 图片死信转人审（bizType=6 单图审 / bizType=1+scene=ARTICLE_IMG_RE_REVIEW 配图复审）
     *
     * @param event 原图片送审事件（死信体）
     */
    public void processImageDeadLetter(ImageSubmitEvent event) {
        // 步骤1（3-6.1）：合法性校验（同文本口径）
        if (event == null || event.getScene() == null || event.getScene().isBlank()) {
            log.error("图片死信缺少 scene，确认丢弃，event={}", event);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        ReviewSceneEnum scene = parseScene(event.getScene());
        if (scene == ReviewSceneEnum.IMAGE) {
            // 步骤2（3-6.2 单图审支线）：IMAGE 不建 review_task（防任务爆炸），无任务可 CAS——
            //        直接发布 SUSPICIOUS 存疑事件，file 侧置 safe_flag=3（绝不自动通过语义由四态闸口承载）
            publishSuspiciousForSingleImage(event);
            return;
        }
        // 步骤2（3-6.2 配图复审支线）：文章级复审任务存在（biz_type=1 + biz_id=articleId，
        //        与 ModerationImageService 定位口径一致）；bizId 非法属脏消息 ack 丢弃
        if (event.getBizId() == null || event.getBizId().isBlank()) {
            log.error("配图复审死信缺少 bizId(articleId)，确认丢弃，event={}", event);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        Long articleId;
        try {
            articleId = Long.valueOf(event.getBizId());
        } catch (NumberFormatException e) {
            log.error("配图复审死信 bizId 非文章ID，确认丢弃，bizId={}", event.getBizId());
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        ReviewTask task = locateTask(ModerationBizTypeEnum.ARTICLE, articleId,
                ReviewSceneEnum.ARTICLE_IMG_RE_REVIEW, "配图复审死信");
        // 步骤2.1：聚合指纹一致性校验（同文本口径）
        verifyHashMatch(task, event.getContentHash(), "配图复审死信");
        // 步骤3（3-6.2）：转人审三件套 CAS；复审死信不联动 safe_flag（见类注释），不发事件
        transitionToManualReview(task, event.getRetryCount(), "配图复审死信");
    }

    /**
     * 单图死信发布 SUSPICIOUS 存疑事件（scene=IMAGE 专用，无任务兑底）
     *
     * <p>复用 3-4 统一发布器必达发布 verdict=SUSPICIOUS(3)；file 侧既有 result 消费链路据此置
     * safe_flag=3（存疑占位图）。发布失败仅 error 留痕不抛：DLQ 无 DLX 兑底，抛异常会被 nack 后
     * 物理丢弃，而 safe_flag 停留 PENDING 时 default-deny 闸口不放行，宁严勿松</p>
     *
     * @param event 原图片送审事件（死信体，bizId=图片 fileId）
     */
    private void publishSuspiciousForSingleImage(ImageSubmitEvent event) {
        String fileId = event.getBizId();
        if (fileId == null || fileId.isBlank()) {
            log.error("单图死信缺少 bizId(fileId)，确认丢弃，event={}", event);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 事件组装口径与主链路一致（ModerationImageService afterCommit 同款）：
        // eventId=UUID / traceId=消费线程 MDC（已恢复死信原链路 ID）/ version=1
        ModerationResultEvent evt = new ModerationResultEvent();
        evt.setEventId(UUID.randomUUID().toString());
        evt.setEventTime(LocalDateTime.now());
        evt.setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
        evt.setVersion(1);
        evt.setBizType(ModerationBizTypeEnum.FILE.getCode());
        evt.setBizId(fileId);
        evt.setScene(ReviewSceneEnum.IMAGE.getCode());
        evt.setContentHash(event.getContentHash());
        evt.setVerdict(VerdictEnum.SUSPICIOUS.getCode());
        // wordlibVer 可空：死信未跑机审无词库版本（事件契约「重投补发场景可空，宁多审不漏审」）
        try {
            moderationResultPublisher.publish(evt);
            log.info("单图死信已发布 SUSPICIOUS 存疑事件，等待 file 侧置 safe_flag=3，fileId={}", fileId);
        } catch (Exception e) {
            log.error("单图死信 SUSPICIOUS 事件发布失败，safe_flag 停留 PENDING（default-deny 不放行），fileId={}", fileId, e);
        }
    }

    /**
     * 死信事件与任务内容指纹一致性校验（对齐主链路 hash 错配分流口径）：
     * 任务已被 upsert 重置（content_hash 变化）时，旧死信转人审会把旧结论安到新内容上，确认丢弃
     *
     * @param task       定位到的审核任务
     * @param eventHash  死信事件携带的内容指纹
     * @param source     日志来源标识
     */
    private void verifyHashMatch(ReviewTask task, String eventHash, String source) {
        if (!java.util.Objects.equals(eventHash, task.getContentHash())) {
            log.error("{}与任务内容指纹不一致，疑似旧消息错配，确认丢弃，taskId={}，eventHash={}，taskHash={}",
                    source, task.getId(), eventHash, task.getContentHash());
            throw new BizException(ErrorCode.MODERATION_HASH_MISMATCH);
        }
    }

    /**
     * 按 (biz_type, biz_id, scene) 三元组定位审核任务（uk_biz_scene 唯一）
     *
     * @param bizType 业务类型枚举
     * @param bizId   业务ID
     * @param scene   审核场景
     * @param source  日志来源标识（文本死信/配图复审死信）
     * @return 审核任务；不存在抛 BizException（消费端 ack 丢弃，重试无意义）
     */
    private ReviewTask locateTask(ModerationBizTypeEnum bizType, Long bizId, ReviewSceneEnum scene, String source) {
        ReviewTask task = reviewTaskMapper.selectOne(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getBizType, bizType)
                .eq(ReviewTask::getBizId, bizId)
                .eq(ReviewTask::getScene, scene));
        if (task == null) {
            log.error("{}定位审核任务不存在，确认丢弃，bizType={}，bizId={}，scene={}",
                    source, bizType.getCode(), bizId, scene.getCode());
            throw new BizException(ErrorCode.MODERATION_TASK_NOT_FOUND);
        }
        return task;
    }

    /**
     * 转人审三件套 CAS（绝不自动通过）：
     * update review_task set status=2(人工待审), mod_verdict=3(SUSPICIOUS), retry_count=事件重试次数
     * where id=? and status=0(待审)
     *
     * <p>CAS 条件 status=0 与板块六 6-2 补偿重发（只扫 status=0）天然互斥，防死信与补偿双路并发处理；
     * affected=0 → 任务已在人审在办（status=2）/已终态（1,3,4）/已被补偿重发，info 留痕后返回
     * （残留竞态窗口靠 CAS 收敛）</p>
     *
     * @param task       死信对应的审核任务
     * @param retryCount 事件体重试次数（null 按 0 对账留痕）
     * @param source     日志来源标识
     */
    private void transitionToManualReview(ReviewTask task, Integer retryCount, String source) {
        int affected = reviewTaskMapper.update(null, new LambdaUpdateWrapper<ReviewTask>()
                .set(ReviewTask::getStatus, ReviewTaskStatusEnum.MANUAL_PENDING)
                .set(ReviewTask::getModVerdict, VerdictEnum.SUSPICIOUS)
                .set(ReviewTask::getRetryCount, retryCount == null ? 0 : retryCount)
                .set(ReviewTask::getUpdateTime, LocalDateTime.now())
                .eq(ReviewTask::getId, task.getId())
                .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.PENDING));
        if (affected == 0) {
            log.info("{}转人审 CAS 落空（任务已被处理），确认放弃，taskId={}，status={}",
                    source, task.getId(), task.getStatus());
        } else {
            log.info("{}已转人审（绝不自动通过），taskId={}，retryCount={}", source, task.getId(), retryCount);
        }
    }

    /**
     * 事件 bizType（Integer）→ ModerationBizTypeEnum 映射（与 ModerationTextService.toBizType 同口径）
     *
     * @param bizType 事件业务类型码
     * @return 枚举；未知码抛 BizException（ack 丢弃脏消息）
     */
    private ModerationBizTypeEnum toBizType(Integer bizType) {
        for (ModerationBizTypeEnum e : ModerationBizTypeEnum.values()) {
            if (e.getCode() == bizType) {
                return e;
            }
        }
        log.error("死信事件 bizType 非法，确认丢弃，bizType={}", bizType);
        throw new BizException(ErrorCode.PARAM_ERROR);
    }

    /**
     * 解析事件场景为 ReviewSceneEnum（code 与枚举名一致，与主链路 parseScene 同口径）
     *
     * @param scene 事件携带的场景码
     * @return 审核场景枚举；非法场景抛 BizException（ack 丢弃脏消息）
     */
    private ReviewSceneEnum parseScene(String scene) {
        try {
            return ReviewSceneEnum.valueOf(scene.trim());
        } catch (IllegalArgumentException e) {
            log.error("死信事件 scene 非法，确认丢弃，scene={}", scene);
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }
}