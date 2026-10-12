package com.litisle.litislereview.sweeper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litisleapi.event.moderation.ModerationAppealResultEvent;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
import com.litisle.litislereview.domain.entity.Appeal;
import com.litisle.litislereview.domain.entity.ModerationRecord;
import com.litisle.litislereview.domain.entity.ReviewTask;
import com.litisle.litislereview.enums.AppealStatusEnum;
import com.litisle.litislereview.enums.ModTypeEnum;
import com.litisle.litislereview.enums.ModerationCategoryEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;
import com.litisle.litislereview.enums.ReviewTaskStatusEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import com.litisle.litislereview.mapper.AppealMapper;
import com.litisle.litislereview.mapper.ModerationRecordMapper;
import com.litisle.litislereview.mapper.ReviewTaskMapper;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import com.litisle.litislereview.mq.publisher.ModerationAppealResultPublisher;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审核任务清扫 sweeper（板块六 6-1，§4.3/§4.2.3）
 *
 * 【四职责合并单入口】间隔 litisle.moderation.sweeper.interval-seconds(60) 外置可调，
 * fixedDelayString 占位符直读（对齐 PunishReconcileSweeper cron 占位符直读惯例）：
 * - 6-1.1 认领超时回池：审核员认领后离线/遗忘导致任务悬死 → CAS 置回待审池重新进入认领视野；
 *   decide 的 CAS 条件（where status=2）天然拦截回池后原审核员的过期裁决，无双裁决风险
 * - 6-1.2 申诉超时自动维持：申诉 7 天未裁决 → 置维持（宁严勿松，防内容/处罚态无限悬空）+
 *   mod_type=6 留痕 + 发布 appeal.result(appealPass=false) 触发下游通知作者（review 侧仅发
 *   事件，通知为消费侧职责，对齐 5-4.3 职责边界；维持原判不改写处罚/缓存/举报态，无需联动）
 * - 6-1.3 积压 Gauge 三态全量刷新 + DLQ 深度：pending/claimed/suspicious 三态缺一会漏监控
 *   （只统计 status=0 会漏「已认领未裁决」与「认领超时未回池」两态）
 * - 6-2 待审超时补偿重发（同任务内分频：每 stuck-resubmit-every-rounds 轮一次）：status=0 超过
 *   stuck-resubmit-minutes 的任务重投 submit 事件（retryCount 取原值不归零，消息头双写对账）；
 *   双投由 3-1 步骤4 CAS where status=0 天然去重，与 DLQ 转人审（置 status=2）天然互斥
 *
 * 【Gauge 口径】moderation_backlog{status=pending|claimed|suspicious}：
 * - pending   = review_task.status=0（待机审，含回池任务）
 * - suspicious= status=2 且 assignee 为空（机审存疑进人审但无人认领）
 * - claimed   = status=2 且 assignee 非空（已认领待人审裁决）
 * Gauge 绑定 AtomicLong 由本轮定时刷新（Prometheus 抓取不打 DB）；DLQ 深度经 AmqpAdmin
 * 被动声明取 messageCount（moderation_dlq_backlog，DLX 兜底链路存量观测）
 *
 * 【互斥与容错】Redis SETNX 互斥锁防多实例同轮重复扫描（value=实例 token 防误删他锁，
 * 对齐 PunishReconcileSweeper）；单条失败仅 error 留痕不阻断整轮，下轮重扫兜底；
 * 回池/维持均为 CAS 条件更新，affected=0 视为并发被抢先处理，跳过即可
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewTaskSweeper {

    /** 清扫互斥锁 key（各段服务端派生，无用户原串拼接） */
    private static final String LOCK_KEY = "review:sweep:review-task:lock";

    /** dedup 前置短锁 key 格式：与机审写入侧 ModerationTextService.DEDUP_KEY 同源口径
     *（review:dedup:{provider.code}:{contentHash}），provider 段取当前配置（与消费端
     * adapter @ConditionalOnProperty 同源值=枚举 code），禁拼用户原串 */
    private static final String DEDUP_KEY_FORMAT = "review:dedup:%s:%s";

    /** 审核任务 Mapper（6-1.1 回池扫描源 / 6-1.3 三态计数源） */
    private final ReviewTaskMapper reviewTaskMapper;

    /** 申诉单 Mapper（6-1.2 超时维持扫描源） */
    private final AppealMapper appealMapper;

    /** 机审留痕 Mapper（6-1.2 超时维持 mod_type=6 留痕写入，结构对齐 AppealServiceImpl.insertRejudgeRecord） */
    private final ModerationRecordMapper moderationRecordMapper;

    /** 申诉裁决结果发布器（6-1.2 超时维持后发 appeal.result(appealPass=false) 触发下游通知作者，复用 5-4.3 收口） */
    private final ModerationAppealResultPublisher appealResultPublisher;

    /** 事务模板（6-1.2 维持 CAS+留痕同事务，execute 返回即已提交=afterCommit 语义，对齐 AppealServiceImpl） */
    private final TransactionTemplate transactionTemplate;

    /** MQ 发送工具（6-2 补偿重发 submit 事件，复用延迟交换机通道保留 retryCount 消息头双写） */
    private final MqSendUtil mqSendUtil;

    /** Micrometer 注册中心（optional 模式：未接 actuator 静默跳过，对齐 WordlibMetrics 惯例） */
    private final ObjectProvider<MeterRegistry> registryProvider;

    /** RabbitMQ 管理端（6-1.3 DLQ 被动声明取 messageCount 深度） */
    private final ObjectProvider<AmqpAdmin> amqpAdminProvider;

    /** Redis 模板（仅互斥锁用途） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 清扫参数（claim-timeout-minutes / appeal-timeout-days / batch-limit / lock-seconds 外置 yml 运营可调） */
    private final ModerationProperties properties;

    // ===== 6-1.3 Gauge 值容器：定时轮询刷新，Gauge 惰性注册绑定（抓取不打 DB） =====

    /** 积压三态计数：status=pending（待机审） */
    private final AtomicLong backlogPending = new AtomicLong(0);

    /** 积压三态计数：status=suspicious（人审待认领） */
    private final AtomicLong backlogSuspicious = new AtomicLong(0);

    /** 积压三态计数：status=claimed（已认领待裁决） */
    private final AtomicLong backlogClaimed = new AtomicLong(0);

    /** DLQ 深度：moderation_dlq_backlog（死信存量，消费失败兑底链路观测） */
    private final AtomicLong dlqBacklog = new AtomicLong(0);

    /** sweep 轮次计数器（6-2 分频执行依据：每 stuck-resubmit-every-rounds 轮执行一次补偿重发） */
    private final AtomicLong sweepRoundCounter = new AtomicLong(0);

    /**
     * 定时清扫入口（fixedDelayString 占位符直读，默认 60s 一轮；四职责顺序执行互不依赖，6-2 分频）
     * <p>互斥锁先行：抢不到说明他实例在扫，本轮直接跳过；finally 中仅当锁仍归属本实例才释放</p>
     */
    @Scheduled(fixedDelayString = "${litisle.moderation.sweeper.interval-seconds:60}",
            timeUnit = TimeUnit.SECONDS)
    public void sweep() {
        // ===== 步骤0：SETNX 抢互斥锁，value=本实例 token（防误删他实例锁） =====
        String token = UUID.randomUUID().toString();
        ModerationProperties.Sweeper cfg = properties.getSweeper();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, token, java.time.Duration.ofSeconds(cfg.getLockSeconds()));
        if (!Boolean.TRUE.equals(locked)) {
            // 他实例持锁中，本轮跳过
            return;
        }
        try {
            // ===== 步骤1：认领超时回池（6-1.1） =====
            int reclaimed = reclaimTimeoutClaims(cfg.getClaimTimeoutMinutes(), cfg.getBatchLimit());

            // ===== 步骤2：申诉超时自动维持（6-1.2） =====
            int maintained = maintainTimeoutAppeals(cfg.getAppealTimeoutDays(), cfg.getBatchLimit());

            // ===== 步骤3：积压 Gauge 三态全量刷新 + DLQ 深度（6-1.3） =====
            refreshBacklogGauges();

            // ===== 步骤4：待审超时补偿重发（6-2，分频门控：每 N 轮一次降扫描开销） =====
            if (sweepRoundCounter.incrementAndGet() % cfg.getStuckResubmitEveryRounds() == 0) {
                resubmitStuckTasks(cfg.getStuckResubmitMinutes(), cfg.getBatchLimit());
            }

            if (reclaimed > 0 || maintained > 0) {
                log.info("审核任务清扫完成：回池={}，申诉超时维持={}", reclaimed, maintained);
            }
        } catch (Exception e) {
            // 整轮失败仅 error 留痕，下轮重扫兜底（清扫为补偿型任务，宁晚勿断）
            log.error("审核任务清扫整轮失败（下轮重扫兜底）", e);
        } finally {
            // 仅当锁仍归属本实例（token 匹配）才释放，防超时后误删他实例新锁
            releaseLock(token);
        }
    }

    /**
     * 认领超时回池（6-1.1）：扫 status=2(人工待审) 且 claim_time 早于 now-claim-timeout-minutes 的任务
     * <p>CAS 条件：where status=2 and assignee=原值——decide 的 CAS（where status=2）天然拦截
     * 回池后原审核员的过期裁决；回池后 claim_time/assignee 清空，任务重新进入公共认领池</p>
     *
     * @param claimTimeoutMinutes 认领超时阈值（分钟）
     * @param batchLimit          单轮批量上限（防全量扫描打满 DB，下轮兜剩余）
     * @return 本轮实际回池任务数
     */
    private int reclaimTimeoutClaims(int claimTimeoutMinutes, int batchLimit) {
        LocalDateTime now = LocalDateTime.now();
        // ===== 步骤1：扫超时已认领任务（status=2，claim_time < now-阈值，limit=batchLimit） =====
        List<ReviewTask> tasks = reviewTaskMapper.selectList(new LambdaQueryWrapper<ReviewTask>()
                .select(ReviewTask::getId)
                .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.MANUAL_PENDING)
                .lt(ReviewTask::getClaimTime, now.minusMinutes(claimTimeoutMinutes))
                .last("LIMIT " + batchLimit));
        if (tasks.isEmpty()) {
            return 0;
        }
        int reclaimed = 0;
        for (ReviewTask task : tasks) {
            try {
                // ===== 步骤2：逐条 CAS 回池（where id=? and status=2；affected=0 视为已被并发处理，跳过） =====
                // decide 的 CAS（where status=2）天然拦截回池后原审核员的过期裁决，无双裁决风险
                reclaimed += reviewTaskMapper.update(null, new LambdaUpdateWrapper<ReviewTask>()
                        .set(ReviewTask::getStatus, ReviewTaskStatusEnum.PENDING)
                        .set(ReviewTask::getAssignee, null)
                        .set(ReviewTask::getClaimTime, null)
                        .set(ReviewTask::getUpdateTime, now)
                        .eq(ReviewTask::getId, task.getId())
                        .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.MANUAL_PENDING));
            } catch (Exception e) {
                // 单条失败不阻断整轮，error 留痕下轮重扫兑底
                log.error("认领超时回池失败，taskId={}", task.getId(), e);
            }
        }
        // ===== 步骤3：回池属调度行为非裁决，不写 moderation_record（口径见注释） =====
        return reclaimed;
    }

    /**
     * 申诉超时自动维持（6-1.2）：扫 appeal.status=0(待裁决) 且 create_time 早于 now-appeal-timeout-days
     * <p>宁严勿松：超时未裁决按维持原判处理，防内容/处罚态无限悬空；单条完整编排为——
     * ① CAS 置 status=1(维持) where id=? and status=0（affected=0 并发跳过）；
     * ② 同事务写 mod_type=6 留痕（结构对齐 AppealServiceImpl.insertRejudgeRecord，reason=超时自动维持）；
     * ③ afterCommit 发布 appeal.result(appealPass=false, reviewTaskId 必带作通知幂等键)——
     *    复用 ModerationAppealResultPublisher 事务顺序铁律（落库失败不发、发布失败入 DLQ）；
     *    维持原判不改写处罚/缓存/举报态（旧结论仍有效），仅触发下游通知作者</p>
     *
     * @param appealTimeoutDays 申诉裁决超时阈值（天）
     * @param batchLimit        单轮批量上限（防全量扫描打满 DB，下轮兜剩余）
     * @return 本轮实际维持申诉数
     */
    private int maintainTimeoutAppeals(int appealTimeoutDays, int batchLimit) {
        // ===== 步骤1：扫超时待裁决申诉（status=0，create_time < now-阈值，limit=batchLimit） =====
        List<Appeal> appeals = appealMapper.selectList(new LambdaQueryWrapper<Appeal>()
                .eq(Appeal::getStatus, AppealStatusEnum.PENDING)
                .lt(Appeal::getCreateTime, LocalDateTime.now().minusDays(appealTimeoutDays))
                .last("LIMIT " + batchLimit));
        int maintained = 0;
        for (Appeal appeal : appeals) {
            try {
                LocalDateTime now = LocalDateTime.now();
                // ===== 步骤2：事务模板内编排（CAS 置维持 + mod_type=6 留痕同事务；失败整体回滚） =====
                ModerationAppealResultEvent event = transactionTemplate.execute(tx -> {
                    int affected = appealMapper.update(null, new LambdaUpdateWrapper<Appeal>()
                            .set(Appeal::getStatus, AppealStatusEnum.MAINTAINED)
                            .set(Appeal::getJudgeBy, null)
                            .set(Appeal::getJudgeOpinion, "超时自动维持：超 " + appealTimeoutDays + " 天未裁决（系统裁决）")
                            .set(Appeal::getDecideTime, now)
                            .set(Appeal::getUpdateTime, now)
                            .eq(Appeal::getId, appeal.getId())
                            .eq(Appeal::getStatus, AppealStatusEnum.PENDING));
                    if (affected == 0) {
                        // 并发被抢先裁决，跳过（事务空转提交，无副作用）
                        return null;
                    }
                    // 同事务留痕：mod_type=6 + verdict=REJECT（维持原驳回结论，reason 为服务端固定串无 CRLF/PII 风险）
                    moderationRecordMapper.insert(new ModerationRecord()
                            .setModType(ModTypeEnum.APPEAL_REJUDGE)
                            .setBizType(appeal.getBizType())
                            .setBizId(appeal.getBizId())
                            .setReviewTaskId(appeal.getReviewTaskId())
                            .setVerdict(VerdictEnum.REJECT)
                            .setCategory(ModerationCategoryEnum.NONE)
                            .setReason("申诉超时自动维持：超 " + appealTimeoutDays + " 天未裁决")
                            .setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY))
                            .setCreateTime(now));
                    // 事件体组装（发布延后至事务提交后，对齐 5-4.3 组装段口径）
                    ModerationAppealResultEvent evt = new ModerationAppealResultEvent();
                    evt.setEventId(UUID.randomUUID().toString());
                    evt.setEventTime(now);
                    evt.setTraceId(MDC.get(MqSendUtil.TRACE_ID_MDC_KEY));
                    evt.setVersion(1);
                    evt.setBizType(appeal.getBizType().getCode());
                    evt.setBizId(String.valueOf(appeal.getBizId()));
                    evt.setAppealId(appeal.getId());
                    evt.setAppealPass(false);
                    evt.setReviewTaskId(appeal.getReviewTaskId());
                    return evt;
                });
                // ===== 步骤3：execute 返回即已提交（afterCommit 语义）才发布；维持不触发处罚/缓存/举报联动 =====
                if (event != null) {
                    appealResultPublisher.publish(event);
                    maintained++;
                }
            } catch (Exception e) {
                // 单条失败不阻断整轮：事务已回滚（状态/留痕均未变更），下轮重扫兑底
                log.error("申诉超时自动维持失败，下轮重试：appealId={}", appeal.getId(), e);
            }
        }
        return maintained;
    }

    /**
     * 待审超时补偿重发（6-2）：扫 status=0(待机审) 且 create_time 早于 now-stuck-resubmit-minutes 的任务，
     * 重投对应 submit 事件重新触发机审——兑底「消费端重试耗尽前的消息丢失/队列异常」场景，
     * 任务永不因消息丢失而永久悬死在待审态
     *
     * <p>【双投安全】重复投递由 3-1 步骤4 的 CAS（where status=0）天然去重：后到消费者
     * affected=0 直接 ACK 丢弃，不产生双份审核结论/双份留痕；与 DLQ 转人审（置 status=2）
     * 天然互斥，CAS 条件隔离两链路</p>
     *
     * <p>【retryCount 口径】重投事件体 retryCount 取 review_task.retry_count 原值不归零
     * （对账留痕口径，3-8.1 消息头递增链路延续），消息头由 sendDelayed 双写保持一致</p>
     *
     * @param stuckResubmitMinutes 待审超时阈值（分钟，须大于消费端最大退避总时长）
     * @param batchLimit           单轮批量上限（下轮兑剩余）
     * @return 本轮实际重发任务数
     */
    private int resubmitStuckTasks(int stuckResubmitMinutes, int batchLimit) {
        // ===== 步骤1：扫超时待审任务（status=PENDING，create_time < now-阈值，limit=batchLimit） =====
        List<ReviewTask> tasks = reviewTaskMapper.selectList(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.PENDING)
                .lt(ReviewTask::getCreateTime, LocalDateTime.now().minusMinutes(stuckResubmitMinutes))
                .last("LIMIT " + batchLimit));
        if (tasks.isEmpty()) {
            return 0;
        }
        int resubmitted = 0;
        for (ReviewTask task : tasks) {
            try {
                LocalDateTime now = LocalDateTime.now();
                String traceId = MDC.get(MqSendUtil.TRACE_ID_MDC_KEY);
                // retryCount 取 review_task.retry_count 原值不归零（对账留痕口径，3-8.1 递增链路延续）
                Integer retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
                // ===== 步骤2：按场景分流组装事件（公共字段 eventId/eventTime/traceId/version 照 5-4.3 口径） =====
                String routingKey;
                String providerCode;
                Object event;
                if (task.getScene() == ReviewSceneEnum.IMAGE) {
                    // 图片：bizId=fileId（字符串），路由键=图片送审
                    ImageSubmitEvent evt = new ImageSubmitEvent();
                    evt.setEventId(UUID.randomUUID().toString());
                    evt.setEventTime(now);
                    evt.setTraceId(traceId);
                    evt.setVersion(1);
                    evt.setModType(1);
                    evt.setBizType(task.getBizType().getCode());
                    evt.setBizId(String.valueOf(task.getBizId()));
                    evt.setScene(task.getScene().getCode());
                    evt.setContentHash(task.getContentHash());
                    evt.setRetryCount(retryCount);
                    event = evt;
                    routingKey = MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT;
                    providerCode = properties.getImageProvider();
                } else {
                    // 文本：bizId=业务主键（Long），路由键=文本送审
                    TextSubmitEvent evt = new TextSubmitEvent();
                    evt.setEventId(UUID.randomUUID().toString());
                    evt.setEventTime(now);
                    evt.setTraceId(traceId);
                    evt.setVersion(1);
                    evt.setModType(1);
                    evt.setBizType(task.getBizType().getCode());
                    evt.setBizId(task.getBizId());
                    evt.setScene(task.getScene().getCode());
                    evt.setContentHash(task.getContentHash());
                    evt.setRetryCount(retryCount);
                    event = evt;
                    routingKey = MqConstant.ROUTING_MODERATION_TEXT_SUBMIT;
                    providerCode = properties.getTextProvider();
                }
                // ===== 步骤3：重发前抢 dedup 前置短锁（同源口径见 DEDUP_KEY_FORMAT 注释，TTL=dedup.lock-seconds）：
                //  SETNX 失败=原请求在途（消费端处理中/延迟重试链路存活）→ 跳过本轮下轮再试，防无谓双发 =====
                String dedupKey = String.format(DEDUP_KEY_FORMAT, providerCode, task.getContentHash());
                String lockToken = UUID.randomUUID().toString();
                Boolean locked = stringRedisTemplate.opsForValue()
                        .setIfAbsent(dedupKey, lockToken,
                                Duration.ofSeconds(properties.getDedup().getLockSeconds()));
                if (!Boolean.TRUE.equals(locked)) {
                    log.info("补偿重发跳过（dedup 前置锁在途，下轮再试），taskId={}，scene={}",
                            task.getId(), task.getScene());
                    continue;
                }
                try {
                    // ===== 步骤4：x-delay=0 经延迟交换机落回主队列（保留 retryCount 消息头双写）；
                    //  不 CAS 改任务状态（保持 status=0，状态流转由消费侧 3-1 步骤4 统一收口） =====
                    mqSendUtil.sendDelayed(MqConstant.EXCHANGE_MODERATION_DELAY,
                            routingKey, event, 0L, retryCount);
                    resubmitted++;
                    log.info("待审超时补偿重发完成，taskId={}，scene={}，retryCount={}",
                            task.getId(), task.getScene(), retryCount);
                } finally {
                    // 释放前置锁：不占住会让消费端步骤8 的 SETNX 失败白耗一轮延迟重试；
                    // GET 比对 token 归属才 DEL（防本锁 TTL 过期后误删消费端新锁，对齐 releaseLock 纪律）
                    if (lockToken.equals(stringRedisTemplate.opsForValue().get(dedupKey))) {
                        stringRedisTemplate.delete(dedupKey);
                    }
                }
            } catch (Exception e) {
                // 单条失败不阻断整轮（任务保持 status=0），error 留痕下轮重试
                log.error("待审超时补偿重发失败，下轮重试：taskId={}", task.getId(), e);
            }
        }
        return resubmitted;
    }

    /**
     * 积压 Gauge 三态全量刷新 + DLQ 深度（6-1.3）
     * <p>三态口径见类注释：pending(status=0)/suspicious(status=2 且 assignee 空)/
     * claimed(status=2 且 assignee 非空)——认领后任务仍在 claimed 态被监控，不脱离视野；
     * Gauge 惰性注册（ObjectProvider<MeterRegistry> optional 模式，未接 actuator 静默跳过，
     * 对齐 WordlibMetrics 惯例），首轮注册后复用，本轮仅刷新 AtomicLong 值；
     * DLQ 深度经 AmqpAdmin passiveDeclare 队列取 messageCount（admin 缺失时置 -1 表示不可观测）</p>
     */
    private void refreshBacklogGauges() {
        // ===== 步骤1：三态计数（status=0 / status=2+assignee null / status=2+assignee 非空） =====
        backlogPending.set(reviewTaskMapper.selectCount(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.PENDING)));
        backlogSuspicious.set(reviewTaskMapper.selectCount(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.MANUAL_PENDING)
                .isNull(ReviewTask::getAssignee)));
        backlogClaimed.set(reviewTaskMapper.selectCount(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.MANUAL_PENDING)
                .isNotNull(ReviewTask::getAssignee)));
        // ===== 步骤2：DLQ 深度（AmqpAdmin 被动查询取 messageCount；text/image 两条送审 DLQ 求和，
        // admin 缺失/查询异常置 -1 表示不可观测，不影响核心清扫） =====
        AmqpAdmin admin = amqpAdminProvider.getIfAvailable();
        if (admin == null) {
            dlqBacklog.set(-1);
        } else {
            try {
                long depth = admin.getQueueInfo(MqConstant.QUEUE_MODERATION_TEXT_SUBMIT_DLQ).getMessageCount()
                        + admin.getQueueInfo(MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT_DLQ).getMessageCount();
                dlqBacklog.set(depth);
            } catch (Exception e) {
                log.error("DLQ 深度查询失败（本轮置 -1 表示不可观测）", e);
                dlqBacklog.set(-1);
            }
        }
        // ===== 步骤3：Gauge 惰性注册（绑定 AtomicLong，同名同标签幂等；未接 actuator 静默跳过） =====
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        Gauge.builder("moderation.backlog", backlogPending, AtomicLong::get)
                .tag("status", "pending").description("审核任务积压（待机审）").register(registry);
        Gauge.builder("moderation.backlog", backlogSuspicious, AtomicLong::get)
                .tag("status", "suspicious").description("审核任务积压（人审待认领）").register(registry);
        Gauge.builder("moderation.backlog", backlogClaimed, AtomicLong::get)
                .tag("status", "claimed").description("审核任务积压（已认领待裁决）").register(registry);
        Gauge.builder("moderation.dlq.backlog", dlqBacklog, AtomicLong::get)
                .description("机审送审 DLQ 死信存量").register(registry);
    }

    /**
     * 释放互斥锁（Lua 比对 token 防误删他实例锁；异常仅 warn 留痕由锁 TTL 自愈）
     *
     * @param token 本实例锁 token
     */
    private void releaseLock(String token) {
        // 对齐 PunishReconcileSweeper 释放纪律：GET 比对 token 归属后才 DEL（执行超时锁已过期被他人抢占时不误删）
        try {
            if (token.equals(stringRedisTemplate.opsForValue().get(LOCK_KEY))) {
                stringRedisTemplate.delete(LOCK_KEY);
            }
        } catch (Exception e) {
            // 释放异常仅 warn 留痕，由锁 TTL 过期自愈
            log.warn("清扫互斥锁释放失败（TTL 自愈），key={}", LOCK_KEY, e);
        }
    }
}
