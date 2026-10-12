package com.litisle.litislereview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.WordLibraryLoader;
import com.litisle.litisleapi.event.moderation.ModerationAppealResultEvent;
import com.litisle.litisleapi.event.moderation.PunishResultEvent;
import com.litisle.litislereview.domain.dto.AppealDecideDTO;
import com.litisle.litislereview.domain.dto.AppealSubmitDTO;
import com.litisle.litislereview.domain.entity.Appeal;
import com.litisle.litislereview.domain.entity.ModerationRecord;
import com.litisle.litislereview.domain.entity.PunishLog;
import com.litisle.litislereview.domain.entity.Report;
import com.litisle.litislereview.domain.entity.ReviewTask;
import com.litisle.litislereview.domain.entity.ViolationScore;
import com.litisle.litislereview.enums.*;
import com.litisle.litislereview.mapper.AppealMapper;
import com.litisle.litislereview.mapper.ModerationRecordMapper;
import com.litisle.litislereview.mapper.PunishLogMapper;
import com.litisle.litislereview.mapper.ReportMapper;
import com.litisle.litislereview.mapper.ReviewTaskMapper;
import com.litisle.litislereview.mapper.ViolationScoreMapper;
import com.litisle.litislereview.moderation.cache.ImageAppealCacheInvalidator;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import com.litisle.litislereview.mq.publisher.ModerationAppealResultPublisher;
import com.litisle.litislereview.mq.publisher.PunishResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.litisle.litislereview.service.AppealService;

/**
 * 申诉链路服务实现（骨架版：逐方法 TODO 占位）
 *
 * 【注入计划（5-4 补充）】judgeAppeal 改判分支还需：
 * - ModerationAppealResultPublisher（5-4.3 appeal.result 发布收口，mq/publisher 包）
 * - StringRedisTemplate（5-4.5 缓存失效：text-result/img-result/last-hash key 删除 + 黑名单移除 sha256）
 * - ModerationConstant（TEXT_RESULT_KEY/IMG_RESULT_KEY/LAST_HASH_KEY/IMG_REJECT_BLACKLIST_KEY 口径复用，禁硬编码）
 *
 * 【5-4 改判 PASS 编排索引】judgeAppeal 撤销分支七步：
 * 入参闸门 → 前置校验（ADMIN/回避/双签） → 改判 CAS（5-4.1） → 同事务联动（5-4.2：
 * 处罚撤销+violation_score 重算+mod_type=6 双签留痕） → 缓存失效（5-4.5） →
 * 举报批量回写（5-4.6） → afterCommit 发 appeal.result（5-4.3；5-4.4 由下游消费侧执行）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AppealServiceImpl implements AppealService {

    /** 申诉表 Mapper：改判 CAS（status 0→1/2） */
    private final AppealMapper appealMapper;

    /** 审核任务 Mapper：原裁决人回避校验 + 缓存键 scene/contentHash 取值 */
    private final ReviewTaskMapper reviewTaskMapper;

    /** 处罚流水 Mapper：撤销 CAS（1→2）+ 违规分重放数据源 */
    private final PunishLogMapper punishLogMapper;

    /** 违规分 Mapper：重算结果覆盖写回 */
    private final ViolationScoreMapper violationScoreMapper;

    /** 举报 Mapper：按内容身份批量回写活跃举报 */
    private final ReportMapper reportMapper;

    /** 机审留痕 Mapper：mod_type=6 双签留痕 */
    private final ModerationRecordMapper moderationRecordMapper;

    /** 图片申诉平反缓存失效器（5-5.2）：按机审留痕行精确重建/删除 img-result/img-blacklist 键 */
    private final ImageAppealCacheInvalidator imageAppealCacheInvalidator;

    /** 5-4.3 appeal.result 事件发布收口（维持/撤销两分支统一出口） */
    private final ModerationAppealResultPublisher appealResultPublisher;

    /** punish.result 事件发布收口（5-2.1 复用）：撤销分支恢复事件 op=2 统一走此出口，禁另起发布分支 */
    private final PunishResultPublisher punishResultPublisher;

    /** Redis 客户端：5-4.5 缓存键删除（text-result/img-result/last-hash/黑名单） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 编程式事务模板：步骤3~6 同事务，execute 返回即已提交（afterCommit 语义） */
    private final TransactionTemplate transactionTemplate;

    /** 词库版本加载器：last-hash/text-result 键 wordlibVer 段取值（与机审判定时同口径） */
    private final WordLibraryLoader wordLibraryLoader;

    /** 机审配置：fewShotVer（文本键段）与图片 provider/modelVersion（图片键段） */
    private final ModerationProperties moderationProperties;

    /** 提交申诉：TODO 归属校验 → 双层防重（关联列级+内容级）→ 落 appeal（bizType/bizId 解析固化） */
    @Override
    public void submitAppeal(AppealSubmitDTO dto, Long appellantId) {
        throw new UnsupportedOperationException("submitAppeal 待实现");
    }

    /**
     * 裁决申诉（板块五 5-4，§4.2.3）：整体编程式事务包裹，撤销分支七步编排
     *
     * 【权限与回避红线】仅 ADMIN（REVIEWER 不可自裁）；原驳回裁决人（review_task.decided_by）
     * 不得裁决该内容申诉（含 ADMIN）；撤销「封禁/违规分清零」属高风险裁决需 confirm=true 双签
     *
     * 【事务边界】步骤3~6 同一本地事务（CAS 失败即整体回滚）；步骤7 在 afterCommit 发事件
     * （事务顺序铁律：落库失败不发，发布失败 IllegalStateException 入 DLQ 延迟重试）
     */
    @Override
    public void judgeAppeal(Long appealId, AppealDecideDTO dto, Long judgeId) {
        // ===== 步骤0：入参闸门 fail-fast =====
        // appealId/judgeId 判空；dto.getStatus() 仅允许终态二值（MAINTAINED/REVOKED），PENDING 拒绝
        if (appealId == null || judgeId == null || dto == null || dto.getStatus() == null
                || dto.getStatus() == AppealStatusEnum.PENDING) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        // ===== 步骤1：前置校验（裁决人身份三关） =====
        // 1a. 按 appealId 查 appeal，不存在抛 APPEAL_NOT_FOUND
        Appeal appeal = appealMapper.selectById(appealId);
        if (appeal == null) {
            throw new BizException(ErrorCode.APPEAL_NOT_FOUND);
        }
        // 1b. ADMIN 权限校验（REVIEWER 不可自裁）：由 AppealController @SaCheckRole("ADMIN") 注解强制，服务层不重复鉴权
        // 1c. 原驳回裁决人回避：回查 review_task.decided_by == judgeId 则拒绝（含 ADMIN）
        //（三元一次性赋值保持 effectively final，供步骤3~7 事务 lambda 捕获）
        ReviewTask reviewTask = appeal.getReviewTaskId() == null
                ? null : reviewTaskMapper.selectById(appeal.getReviewTaskId());
        if (reviewTask != null && judgeId.equals(reviewTask.getDecidedBy())) {
            throw new BizException(ErrorCode.APPEAL_JUDGE_FORBIDDEN);
        }

        // ===== 步骤2：高风险裁决双签确认（仅撤销分支） =====
        // 撤销「封禁/违规分清零」需 dto.confirm=true，缺失拒绝；维护分支无需 confirm
        // 高风险判定（撤销目标含 action=2-封禁生效行）前置到事务外 fail-fast，不占事务时长
        boolean revoked = dto.getStatus() == AppealStatusEnum.REVOKED;
        // 撤销分支预定位生效处罚行（事务外 fail-fast；事务 lambda 内复用，一次性赋值保持 effectively final）
        List<PunishLog> effectivePunishes = revoked ? locateEffectivePunishes(appeal) : List.of();
        if (revoked) {
            boolean highRisk = effectivePunishes.stream()
                    .anyMatch(p -> p.getAction() == PunishActionEnum.BAN);
            if (highRisk && !Boolean.TRUE.equals(dto.getConfirm())) {
                throw new BizException(ErrorCode.APPEAL_CONFIRM_REQUIRED);
            }
        }

        // ===== 步骤3~6：同一本地事务（CAS 失败即整体回滚；execute 返回即已提交=afterCommit 语义） =====
        // 撤销成功的处罚行收集器（事务 lambda 内填充，提交后组装 op=2 恢复事件发 punish.result）
        List<PunishLog> revokedPunishes = new ArrayList<>();
        ModerationAppealResultEvent event = transactionTemplate.execute(tx -> {
            // ----- 步骤3：改判 CAS（5-4.1）-----
            // update appeal set status/judge_by/judge_opinion/decide_time
            //   where id=? and status=0；affected=0 → 70004（已被裁决/并发冲突）
            LocalDateTime now = LocalDateTime.now();
            int affected = appealMapper.update(null, new LambdaUpdateWrapper<Appeal>()
                    .set(Appeal::getStatus, dto.getStatus())
                    .set(Appeal::getJudgeBy, judgeId)
                    .set(Appeal::getJudgeOpinion, dto.getJudgeOpinion())
                    .set(Appeal::getDecideTime, now)
                    .set(Appeal::getUpdateTime, now)
                    .eq(Appeal::getId, appealId)
                    .eq(Appeal::getStatus, AppealStatusEnum.PENDING));
            if (affected == 0) {
                throw new BizException(ErrorCode.TASK_ALREADY_CLAIMED);
            }

            // ----- 步骤4：撤销分支同事务联动（5-4.2，仅 REVOKED）-----
            if (revoked) {
                // 4a 前置：被处罚人取处罚行 user_id（无生效行时兜底取申诉人=内容归属人）
                Long punishUserId = effectivePunishes.isEmpty()
                        ? appeal.getAppellantId() : effectivePunishes.get(0).getUserId();
                // 4a. 处罚撤销：逐行 CAS status 1→2 + revoke_time（仅生效行，防撤销已撤销行）
                revokedPunishes.addAll(revokePunishes(effectivePunishes, now));
                // 4b. violation_score 重算：重放该用户全部 action∈{1,2} 生效行覆盖写回
                recalcViolationScore(punishUserId, now);
                // 4c. 双签留痕：moderation_record 追加 mod_type=6-申诉改判行
                insertRejudgeRecord(appeal, reviewTask, dto, judgeId, now);
            }

            // ----- 步骤5：缓存层同步失效（5-4.5；删除异常仅 error 留痕不阻断主流程）-----
            invalidateModerationCache(appeal, reviewTask);

            // ----- 步骤6：按内容身份回写同内容全部活跃举报（5-4.6，仅 REVOKED）-----
            if (revoked) {
                rewriteActiveReports(appeal, judgeId, now);
            }

            // ----- 步骤7（组装段）：组装 appeal.result 事件体（发布延后至事务提交后）-----
            // reviewTaskId 必带作下游 notification 幂等键；appealPass=revoked 区分维持/撤销
            ModerationAppealResultEvent evt = new ModerationAppealResultEvent();
            evt.setEventId(UUID.randomUUID().toString());
            evt.setEventTime(now);
            evt.setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
            evt.setVersion(1);
            evt.setBizType(appeal.getBizType().getCode());
            evt.setBizId(String.valueOf(appeal.getBizId()));
            evt.setAppealId(appeal.getId());
            evt.setAppealPass(revoked);
            evt.setReviewTaskId(appeal.getReviewTaskId());
            return evt;
        });

        // ===== 步骤7：afterCommit 发 appeal.result（5-4.3；事务顺序铁律：提交后才发，失败 IllegalStateException 入 DLQ 兜底） =====
        // 注：5-4.4 content/file/message 恢复由下游消费侧执行，review 侧仅发事件
        appealResultPublisher.publish(event);

        // ===== 步骤7b（撤销分支）：被撤销的封禁生效行补发 punish.result 恢复事件（op=2） =====
        // 事务顺序铁律：提交后才发；仅 action=2-封禁发恢复事件（触发 auth/user 解封快照回写，
        // 漏发由 5-3 反向对账 sweeper 兜底）；禁言撤销不发（frozen_until 清空已随重算落库，到点自然解除）
        for (PunishLog punish : revokedPunishes) {
            if (punish.getAction() != PunishActionEnum.BAN) {
                continue;
            }
            PunishResultEvent evt = new PunishResultEvent();
            evt.setEventId(UUID.randomUUID().toString());
            evt.setEventTime(LocalDateTime.now());
            evt.setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
            evt.setVersion(1);
            evt.setUserId(punish.getUserId());
            evt.setAction(PunishResultEvent.ACTION_BAN);
            evt.setOp(PunishResultEvent.OP_REVOKE);
            evt.setPunishLogId(punish.getId());
            evt.setFrozenUntil(null);
            punishResultPublisher.publish(evt);
        }
    }

    /**
     * 定位撤销目标的生效处罚行（步骤2/4a 数据源）：appeal.punish_id 非空直用（仅取生效行）；
     * 否则按 review_task_id 反查 punish_log 生效行（5-4 步骤2 定位口径）
     *
     * @param appeal 申诉单
     * @return 生效处罚行列表（无关联或已全部撤销返回空列表，撤销分支降级为仅重算违规分）
     */
    private List<PunishLog> locateEffectivePunishes(Appeal appeal) {
        // 步骤1：punish_id 非空直用——selectById 后过滤 status=1 生效行（已撤销行不再撤销）
        if (appeal.getPunishId() != null) {
            PunishLog punish = punishLogMapper.selectById(appeal.getPunishId());
            return punish != null && punish.getStatus() == PunishStatusEnum.EFFECTIVE
                    ? List.of(punish) : List.of();
        }
        // 步骤2：punish_id 为空按 review_task_id 反查该任务全部生效处罚行
        if (appeal.getReviewTaskId() != null) {
            return punishLogMapper.selectList(new LambdaQueryWrapper<PunishLog>()
                    .eq(PunishLog::getReviewTaskId, appeal.getReviewTaskId())
                    .eq(PunishLog::getStatus, PunishStatusEnum.EFFECTIVE));
        }
        return List.of();
    }

    /**
     * 处罚撤销（5-4.2 4a）：逐行 CAS status 1→2 + revoke_time，仅生效行可撤（防撤销已撤销行）
     *
     * @param punishes 生效处罚行列表
     * @param now      撤销时间
     * @return 实际撤销成功的行（CAS affected=1），供事务提交后组装 op=2 恢复事件
     */
    private List<PunishLog> revokePunishes(List<PunishLog> punishes, LocalDateTime now) {
        List<PunishLog> revoked = new ArrayList<>(punishes.size());
        for (PunishLog punish : punishes) {
            // 步骤1：CAS 撤销（where id=? and status=1）；affected=0=已被并发撤销，跳过不阻断
            int affected = punishLogMapper.update(null, new LambdaUpdateWrapper<PunishLog>()
                    .set(PunishLog::getStatus, PunishStatusEnum.REVOKED)
                    .set(PunishLog::getRevokeTime, now)
                    .eq(PunishLog::getId, punish.getId())
                    .eq(PunishLog::getStatus, PunishStatusEnum.EFFECTIVE));
            if (affected > 0) {
                revoked.add(punish);
            }
        }
        return revoked;
    }

    /**
     * violation_score 重算覆盖写回（5-4.2 4b）：重放该用户全部 status=1 生效行 append-only 流水
     * <p>score=全部生效行累计（展示/预警分，含 action=3，与 RejectPunishAdapterHost 累加口径一致）；
     * last_punish_action/frozen_until 仅由 action∈{1,2} 生效行推导——禁言撤销后无生效禁言行即清
     * frozen_until（对齐 5-4 步骤2「action=1 撤销清 frozen_until」），封禁行推导 lastPunishAction=BAN</p>
     *
     * @param userId 被处罚用户 ID
     * @param now    重算时间
     */
    private void recalcViolationScore(Long userId, LocalDateTime now) {
        // 步骤1：查全部生效行（撤销 CAS 已生效，status=2 行天然排除），按处罚时间倒序取最近生效禁言/封禁
        List<PunishLog> effective = punishLogMapper.selectList(new LambdaQueryWrapper<PunishLog>()
                .eq(PunishLog::getUserId, userId)
                .eq(PunishLog::getStatus, PunishStatusEnum.EFFECTIVE)
                .orderByDesc(PunishLog::getCreateTime)
                .orderByDesc(PunishLog::getId));
        // 步骤2：展示/预警分 = 全部生效行 score 快照累计（含 action=3-内容下架行）
        int score = effective.stream().mapToInt(p -> p.getScore() == null ? 0 : p.getScore()).sum();
        // 步骤3：last_punish_action/frozen_until 由最近的 action∈{1,2} 生效行推导（禁言带 expire_time，封禁永久 null）
        LastPunishActionEnum lastAction = LastPunishActionEnum.NONE;
        LocalDateTime frozenUntil = null;
        for (PunishLog p : effective) {
            if (p.getAction() == PunishActionEnum.MUTE) {
                lastAction = LastPunishActionEnum.MUTE;
                frozenUntil = p.getExpireTime();
                break;
            }
            if (p.getAction() == PunishActionEnum.BAN) {
                lastAction = LastPunishActionEnum.BAN;
                break;
            }
        }
        // 步骤4：覆盖写回（先 update；行不存在兜底 insert，主键冲突转重试对齐 RejectPunishAdapterHost 两段式）
        writeViolationScore(userId, score, lastAction, frozenUntil, now);
    }

    /**
     * violation_score 覆盖写回（update 优先 + insert 兜底 + 冲突重试的两段式并发兜底，
     * 与 RejectPunishAdapterHost.accumulateViolationScore 同款防首插竞态纪律）
     *
     * @param userId       用户 ID
     * @param score        重算后展示/预警分
     * @param lastAction   推导后的上次处罚动作
     * @param frozenUntil  推导后的禁言截止时间（无生效禁言行时为 null）
     * @param now          重算时间
     */
    private void writeViolationScore(Long userId, int score, LastPunishActionEnum lastAction,
                                     LocalDateTime frozenUntil, LocalDateTime now) {
        int affected = violationScoreMapper.update(null, new LambdaUpdateWrapper<ViolationScore>()
                .set(ViolationScore::getScore, score)
                .set(ViolationScore::getLastPunishAction, lastAction)
                .set(ViolationScore::getFrozenUntil, frozenUntil)
                .set(ViolationScore::getUpdateTime, now)
                .eq(ViolationScore::getUserId, userId));
        if (affected == 0) {
            try {
                violationScoreMapper.insert(new ViolationScore()
                        .setUserId(userId)
                        .setScore(score)
                        .setLastPunishAction(lastAction)
                        .setFrozenUntil(frozenUntil)
                        .setUpdateTime(now));
            } catch (DuplicateKeyException e) {
                // 并发首插冲突转重试 update（保证重算结果不丢）
                violationScoreMapper.update(null, new LambdaUpdateWrapper<ViolationScore>()
                        .set(ViolationScore::getScore, score)
                        .set(ViolationScore::getLastPunishAction, lastAction)
                        .set(ViolationScore::getFrozenUntil, frozenUntil)
                        .set(ViolationScore::getUpdateTime, now)
                        .eq(ViolationScore::getUserId, userId));
            }
        }
    }

    /**
     * 双签留痕（5-4.2 4c）：moderation_record 追加 mod_type=6-申诉改判行（append-only，改判追加新行）
     * <p>撤销分支专用：verdict 固定 PASS；scene/contentHash 自 review_task 回填（场景列自包含免 JOIN）；
     * category 无机审类目语义固定 none；operator_id=裁决人（双签审计留痕）</p>
     *
     * @param appeal     申诉单（bizType/bizId/reviewTaskId 取值源）
     * @param reviewTask 关联审核任务（scene/contentHash 取值源，可为 null）
     * @param dto        裁决入参（judge_opinion 作留痕 reason）
     * @param judgeId    裁决人 ID
     * @param now        留痕时间
     */
    private void insertRejudgeRecord(Appeal appeal, ReviewTask reviewTask,
                                     AppealDecideDTO dto, Long judgeId, LocalDateTime now) {
        moderationRecordMapper.insert(new ModerationRecord()
                .setModType(ModTypeEnum.APPEAL_REJUDGE)
                .setBizType(appeal.getBizType())
                .setBizId(appeal.getBizId())
                .setReviewTaskId(appeal.getReviewTaskId())
                .setScene(reviewTask != null ? reviewTask.getScene() : null)
                .setContentHash(reviewTask != null ? reviewTask.getContentHash() : null)
                .setVerdict(VerdictEnum.PASS)
                .setCategory(ModerationCategoryEnum.NONE)
                .setReason(dto.getJudgeOpinion())
                .setOperatorId(judgeId)
                .setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY))
                .setCreateTime(now));
    }

    /**
     * 审核缓存同步失效（5-4.5 + 5-5.2 委派）：文本场景删 text-result/last-hash key，
     * 图片（bizType=6）委派 ImageAppealCacheInvalidator 按留痕行删 img-result/黑名单键
     * <p>文本键口径与机审判定时完全对齐（wordlibVer 走 WordLibraryLoader.loadVersion、
     * fewShotVer 走 ModerationProperties），禁硬编码防版本漂移；图片键按 moderation_record
     * 留痕行逐行重建（provider/model_version/content_hash 随判定固化），解决聚合指纹错位
     * 与单图审无任务两缺口；失败仅 error 留痕不阻断裁决事务（宁严勿松由 TTL 过期兜底）</p>
     *
     * @param appeal     申诉单（bizType/bizId 取值源）
     * @param reviewTask 关联审核任务（scene/contentHash 取值源，可为 null）
     */
    private void invalidateModerationCache(Appeal appeal, ReviewTask reviewTask) {
        try {
            // 步骤0：bizType=6（图片）委派 ImageAppealCacheInvalidator 按机审留痕行精确删键（5-5.2）：
            // 解决既有粗粒度拼键两缺口——① 配图复审任务 content_hash 为聚合指纹，拼单图键必错位漏删；
            // ② 单图审（scene=IMAGE）不建 review_task，reviewTask=null 时缓存失效整体丢失。
            // 图片场景无文本键，委派后直接返回；失败语义由组件内部兜底（仅 error 留痕不抛）
            if (appeal.getBizType() == ModerationBizTypeEnum.FILE) {
                imageAppealCacheInvalidator.invalidate(appeal.getBizId());
                return;
            }
            // 步骤0b：文本场景无关联任务时无 scene/contentHash 来源，无可删键（机审驳回申诉必有任务，防御性跳过）
            if (reviewTask == null) {
                log.warn("申诉改判缓存失效跳过：申诉无关联审核任务，appealId={}，bizType={}",
                        appeal.getId(), appeal.getBizType());
                return;
            }
            String scene = reviewTask.getScene().getCode();
            String contentHash = reviewTask.getContentHash();
            Set<String> keys = new HashSet<>();
            // 步骤1：文本场景删 text-result + last-hash 键（wordlibVer 与机审判定时同源，防版本漂移）
            String wordScene = mapWordScene(reviewTask.getScene());
            if (wordScene != null && contentHash != null && !contentHash.isEmpty()) {
                long wordlibVer = wordLibraryLoader.loadVersion(wordScene);
                keys.add(String.format(ModerationConstant.TEXT_RESULT_KEY,
                        wordlibVer, moderationProperties.getLlm().getFewShotVersion(), scene, contentHash));
                keys.add(String.format(ModerationConstant.LAST_HASH_KEY,
                        wordlibVer, appeal.getBizType().getCode(), scene, appeal.getBizId()));
            }
            // 步骤3：一次性批量删除（keys 非空才执行）
            if (!keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.error("申诉改判缓存失效失败（不阻断裁决主流程，TTL 过期兜底），appealId={}", appeal.getId(), e);
        }
    }

    /**
     * 审核场景 → DFA 词库场景映射（与 ModerationTextService.requireWordScene 同口径；
     * 图片场景返回 null 不查词库版本，勿误调词库）
     *
     * @param scene 审核场景
     * @return 词库场景码（ARTICLE/COMMENT/MESSAGE/USERNAME）；图片场景返回 null
     */
    private String mapWordScene(ReviewSceneEnum scene) {
        return switch (scene) {
            case ARTICLE_FEATURED, ARTICLE_COMMUNITY, ARTICLE_RE_REVIEW -> "ARTICLE";
            case COMMENT, COMMENT_RE_REVIEW -> "COMMENT";
            case MESSAGE, MESSAGE_RE_REVIEW -> "MESSAGE";
            case USERNAME -> "USERNAME";
            default -> null;
        };
    }

    /**
     * 解析图片机审 provider（与 ModerationImageService.resolveImageProvider 同口径，
     * 保证 img-result 键 provider 段与机审写入时一致）
     *
     * @return 图片机审 provider 枚举
     */
    private ModerationProviderEnum resolveImageProvider() {
        for (ModerationProviderEnum e : ModerationProviderEnum.values()) {
            if (e.getCode().equals(moderationProperties.getImageProvider())) {
                return e;
            }
        }
        throw new IllegalStateException("未知图片机审 provider：" + moderationProperties.getImageProvider());
    }

    /**
     * 按内容身份批量回写活跃举报（5-4.6）：biz_type→target_type 错位映射（禁恒等，5↔4、6↔5），
     * 收敛同内容全部 status<3 活跃举报置已裁决，非仅 appeal.report_id 那一份
     * （防同一内容多路径举报交叉申诉绕过防重）
     *
     * @param appeal  申诉单（bizType/bizId 为内容身份）
     * @param judgeId 裁决人 ID（回写 handler 留痕）
     * @param now     回写时间
     */
    private void rewriteActiveReports(Appeal appeal, Long judgeId, LocalDateTime now) {
        // 步骤1：biz_type→target_type 错位映射（MESSAGE 5→4、FILE 6→5；用户名/作家申请不可举报无回写目标）
        TargetTypeEnum targetType = switch (appeal.getBizType()) {
            case ARTICLE -> TargetTypeEnum.ARTICLE;
            case COMMENT -> TargetTypeEnum.COMMENT;
            case MESSAGE -> TargetTypeEnum.MESSAGE;
            case FILE -> TargetTypeEnum.IMAGE;
            default -> null;
        };
        if (targetType == null) {
            log.info("申诉改判举报回写跳过：bizType={} 不可被举报无活跃举报，appealId={}，bizId={}",
                    appeal.getBizType(), appeal.getId(), appeal.getBizId());
            return;
        }
        // 步骤2：按 target_type+target_id 批量回写 status<3 活跃举报 → 3-已裁决 + handler/结果留痕
        reportMapper.update(null, new LambdaUpdateWrapper<Report>()
                .set(Report::getStatus, ReportStatusEnum.DECIDED)
                .set(Report::getResult, "申诉改判PASS")
                .set(Report::getHandler, judgeId)
                .set(Report::getUpdateTime, now)
                .eq(Report::getTargetType, targetType)
                .eq(Report::getTargetId, appeal.getBizId())
                .in(Report::getStatus, ReportStatusEnum.PENDING,
                        ReportStatusEnum.REVIEWING, ReportStatusEnum.MANUAL));
    }
}
