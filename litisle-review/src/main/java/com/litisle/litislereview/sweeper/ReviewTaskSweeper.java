package com.litisle.litislereview.sweeper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.litisle.litislereview.support.RedisLockReleaseSupport;
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
 *
 * ReviewTaskSweeper 是一个 Spring 定时任务组件，每 60 秒执行一轮，负责：
 * 1.认领超时回池
 * 2.申诉超时自动维持
 * 3.审核积压监控指标刷新
 * 4.待审任务超时补偿重发
 * 5.它保证审核任务不会因为人工离线、消息丢失、申诉无人处理而永久卡死。
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

    /** 积压 Gauge 指标名（三态共用 + status 标签区分；重复字面量收敛为常量防拼写漂移，对齐 ModerationMetrics 惯例） */
    private static final String METRIC_BACKLOG = "moderation.backlog";

    /** DLQ 死信存量 Gauge 指标名（独立指标，无 status 维度） */
    private static final String METRIC_DLQ_BACKLOG = "moderation.dlq.backlog";

    /** 积压 Gauge 的 status 标签 key 及取值（pending=待机审 / suspicious=人审待认领 / claimed=已认领待裁决） */
    private static final String TAG_STATUS = "status";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_SUSPICIOUS = "suspicious";
    private static final String STATUS_CLAIMED = "claimed";

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

    /** 互斥锁原子释放工具（Lua 比对 token 后删除，消除 GET 与 DEL 之间的误删窗口） */
    private final RedisLockReleaseSupport lockReleaseSupport;

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
     * 定时清扫入口（fixedDelayString 占位符直读，默认 60s 一轮；四职责顺序执行互不依赖，6-2 分频
     *
     *定时任务主入口，调度所有清扫动作
     * 作用：定时任务主入口，负责抢锁、按顺序调用四个清扫动作、最后安全释放锁。
     *
     * 所以正常态下每轮扫描就是：几次索引查询 + 零命中返回，毫秒级。 Sweeper 是补偿型兜底，
     * 不是主链路 ——任务流转靠 MQ 消费驱动，Sweeper 只捞“消息丢了/审核员离线/申诉没人管”的例外，例外是稀少的。
     *性能上 1min 完全没问题
     *扫描只查异常状态任务（`status=0/2` ），走`idx_status` /`idx_claim` 索引，健康数据零扫描——查询本身是微秒到毫秒级，
     *
     *
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
     * 作用：把审核员认领后长时间未裁决的任务，CAS 退回公共待审池。
     *
     * @param claimTimeoutMinutes 认领超时阈值（分钟）
     * @param batchLimit          单轮批量上限（防全量扫描打满 DB，下轮兜剩余）
     * @return 本轮实际回池任务数
     */
    private int reclaimTimeoutClaims(int claimTimeoutMinutes, int batchLimit) {
        LocalDateTime now = LocalDateTime.now();
        // ===== 步骤1：扫超时已认领任务（status=2，claim_time < now-阈值，limit=batchLimit） =====
        // 用 Page 替代 .last("LIMIT")：分页拦截器标准方式，无裸 SQL 字符串（searchCount=false 跳过 COUNT）
        List<ReviewTask> tasks = reviewTaskMapper.selectList(Page.of(1, batchLimit, false),
                new LambdaQueryWrapper<ReviewTask>()
                        .select(ReviewTask::getId)
                        .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.MANUAL_PENDING)
                        .lt(ReviewTask::getClaimTime, now.minusMinutes(claimTimeoutMinutes)));
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
        // 7-3 告警②数据支撑：回池计数器（Prometheus 渲染 moderation_claim_timeout_repool_total）——
        // 规则用 increase(...[15m]) > 0 表达「持续出现认领超时」，claim.timeout-minutes 阈值变更
        // 在 Nacos 改即间接改变告警触发点，无需改规则文件（阈值外置口径）
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry != null && reclaimed > 0) {
            registry.counter("moderation.claim.timeout.repool").increment(reclaimed);
        }
        return reclaimed;
    }

    /**
     * 申诉超时自动维持（6-1.2）：扫 appeal.status=0(待裁决) 且 create_time 早于 now-appeal-timeout-days
     *
     * 作用：申诉超过 N 天未裁决，按"维持原判"自动处理，并留痕+通知作者。
     *
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
        // 用 Page 替代 .last("LIMIT")：分页拦截器标准方式，无裸 SQL 字符串（searchCount=false 跳过 COUNT）
        List<Appeal> appeals = appealMapper.selectList(Page.of(1, batchLimit, false),
                new LambdaQueryWrapper<Appeal>()
                        .eq(Appeal::getStatus, AppealStatusEnum.PENDING)
                        .lt(Appeal::getCreateTime, LocalDateTime.now().minusDays(appealTimeoutDays)));
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
     * 作用：扫描长时间卡在 status=0 的任务，重新投递 submit 事件触发机审，兜底消息丢失。
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
        // 用 Page 替代 .last("LIMIT")：分页拦截器标准方式，无裸 SQL 字符串（searchCount=false 跳过 COUNT）
        List<ReviewTask> tasks = reviewTaskMapper.selectList(Page.of(1, batchLimit, false),
                new LambdaQueryWrapper<ReviewTask>()
                        .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.PENDING)
                        .lt(ReviewTask::getCreateTime, LocalDateTime.now().minusMinutes(stuckResubmitMinutes)));
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
                    // 改由 Lua 原子执行「取值比对 + 删除」，防本锁 TTL 过期后误删消费端新锁
                    lockReleaseSupport.releaseIfOwned(dedupKey, lockToken, "补偿重发dedup前置锁");
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
     *
     * 作用：全量统计审核任务三态积压数 + DLQ 死信深度，刷新 Micrometer Gauge 供 Prometheus 抓取。
     *把当前审核系统的“积压状态”和“死信队列深度”统计出来，刷新到 Micrometer 的 Gauge 指标里，供 Prometheus / Grafana 监控抓取。
     * 只为检测
     *
     * <p>三态口径见类注释：pending(status=0)/suspicious(status=2 且 assignee 空)/
     * claimed(status=2 且 assignee 非空)——认领后任务仍在 claimed 态被监控，不脱离视野；
     * Gauge 惰性注册（ObjectProvider<MeterRegistry> optional 模式，未接 actuator 静默跳过，
     * 对齐 WordlibMetrics 惯例），首轮注册后复用，本轮仅刷新 AtomicLong 值；
     * DLQ 深度经 AmqpAdmin passiveDeclare 队列取 messageCount（admin 缺失时置 -1 表示不可观测）</p>
     *
     *
     * 每轮 Sweeper 执行时，它会：
     * 1.统计审核任务的三类积压数量
     * 2.查询 RabbitMQ 死信队列（DLQ）深度
     * 3.把这些数字写进 Micrometer Gauge
     * 4.Prometheus 定时来抓这些 Gauge 的值
     * 5.Grafana 上就能看到实时积压图
     */
    private void refreshBacklogGauges() {
        // ===== 步骤1：三态计数 —— 给运营/监控提供「审核链路健康度」的三个仪表盘读数 =====
            // 业务意义：审核链路分三段（机审消费→人审认领→人审裁决），每段堵了症状不同，
            // 必须分开计量才能定位瓶颈：pending 高=机审消费卡死；suspicious 高=没人认领（人手不足）；
            // claimed 高=认领了但迟迟不裁决（审核员息或单人占满配额）。
            // 实现上仅做 COUNT(*) 三连查，无写操作，对清扫主流程零侵入
        // 态① 待机审积压：status=0（机审待处理/超时回池后仍是 0），反映机审链路吞吐压力
        backlogPending.set(reviewTaskMapper.selectCount(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.PENDING))); //待审
        // 态② 人审待认领积压：status=2（人工待审）且 assignee IS NULL（无人认领），
         // 反映人审队列入口的排队量，是审核员侧「待领取」池的深度
        backlogSuspicious.set(reviewTaskMapper.selectCount(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.MANUAL_PENDING)//人工待审 isNULL
                .isNull(ReviewTask::getAssignee)));
        // 态③ 已认领待裁决积压：status=2 且 assignee IS NOT NULL（已认领未裁决），
            // 认领后任务仍留在监控视野内不脱离（对齐类注释口径：认领不等于出队），
            // 该值异常偏高通常意味着审核员认领后长期未裁决（可结合 claim 超时回池告警）
        backlogClaimed.set(reviewTaskMapper.selectCount(new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getStatus, ReviewTaskStatusEnum.MANUAL_PENDING)//人工待审 NotNULL
                .isNotNull(ReviewTask::getAssignee)));
        // ===== 步骤2：DLQ 深度 —— 机审「死信告警」的数据源 =====
            // 业务意义：DLQ 里出现消息 = 机审消费链路反复失败（模型不可用/MQ 异常），
            // 任务已脱离正常重试，只能靠人工/补偿扫描捞回；本读数就是 Grafana 死信告警的取数来源。
            // getIfAvailable 语义（不常见 API 简释）：AmqpAdmin 仅在引入 amqp starter 时才存在，
            // ObjectProvider 包住可选依赖，有 Bean 返实例、没有返 null 而非启动报错——
            // 监控埋点是锦上添花，绝不能因未接监控让核心清扫启动失败
                //amqpAdminProvider.getIfAvailable():从 Spring 容器中“按需取”一个 AmqpAdmin 实例，拿不到就返回 null，不抛异常。
                // AmqpAdmin 是 Spring AMQP 提供的 RabbitMQ 管理接口，在这里用来查 DLQ 里还有多少条死信。
        AmqpAdmin admin = amqpAdminProvider.getIfAvailable();
        if (admin == null) {
            // 没接 RabbitMQ 管理客户端：无法观测，记 -1（告警规则会排除 -1，不会误报）
            dlqBacklog.set(-1);
        } else {
            try {
                // getQueueInfo 内部走 AMQP passive declare：不创建队列、仅向 broker 回查队列声明信息，
                // 从返回的 QueueInfo.messageCount 拿到 ready 消息数（不含 unacked）；
                // text/image 两条送审 DLQ 求和 = 机审链路死信总存量，>0 持续增长即需人工介入
                long depth = admin.getQueueInfo(MqConstant.QUEUE_MODERATION_TEXT_SUBMIT_DLQ).getMessageCount()
                        + admin.getQueueInfo(MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT_DLQ).getMessageCount();
                dlqBacklog.set(depth);
            } catch (Exception e) {
                // 查询异常（如队列未声明/broker 抖动）：仅 error 留痕，本轮置 -1，
                // 不向上抛——Gauge 刷新失败不能影响核心清扫主流程
                log.error("DLQ 深度查询失败（本轮置 -1 表示不可观测）", e);
                dlqBacklog.set(-1);
            }
        }
        // ===== 步骤3：把读数挂到监控暴露端点 —— 供 Prometheus 周期抓取 =====
            // 业务意义：上面算出的三个 AtomicLong 只是内存数字，必须挂到 MeterRegistry 才会被
            // /actuator/prometheus 端点吐出、被 Prometheus 抓走画进面板；未接 actuator 时
            // registry 为 null，跳过暴露但计数照常刷新（以后接了监控无需改代码即恢复）
            //MeterRegistry 是 Micrometer​ 的核心接口，所有监控指标都要通过它来注册和上报。
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
            // 首轮执行时把「读某个 AtomicLong」这个动作注册成 Gauge，之后每轮只会刷新数值，
            // 重复注册同名同标签是幂等的不会产生重复指标；三态共用一个指标名+status 标签区分，
            // Grafana 侧按 status=pending/suspicious/claimed 拆三条曲线
            //把四个 AtomicLong 变量注册成 Prometheus Gauge 指标，让监控系统能定时抓到当前积压数。
        Gauge.builder(METRIC_BACKLOG, backlogPending, AtomicLong::get)
                .tag(TAG_STATUS, STATUS_PENDING).description("审核任务积压（待机审）").register(registry);
        Gauge.builder(METRIC_BACKLOG, backlogSuspicious, AtomicLong::get)
                .tag(TAG_STATUS, STATUS_SUSPICIOUS).description("审核任务积压（人审待认领）").register(registry);
        Gauge.builder(METRIC_BACKLOG, backlogClaimed, AtomicLong::get)
                .tag(TAG_STATUS, STATUS_CLAIMED).description("审核任务积压（已认领待裁决）").register(registry);
        // DLQ 存量为独立指标（无 status 维度）；抓取值为 -1 时代表不可观测，可在告警规则里排除
        Gauge.builder(METRIC_DLQ_BACKLOG, dlqBacklog, AtomicLong::get)
                .description("机审送审 DLQ 死信存量").register(registry);
    }

    /**
     * 释放互斥锁（Lua 原子比对 token 防误删他实例锁；异常仅 warn 留痕由锁 TTL 自愈）
     *
     * 作用：比对 token 后删除 Redis 互斥锁，防止误删其他实例的锁。
     *
     * @param token 本实例锁 token
     */
    private void releaseLock(String token) {
        // 原实现是 GET 比对后再 DEL 两步：两步之间锁若 TTL 到期被他人抢占，DEL 会误删他人的锁。
        // 改为 Lua 原子执行，取值、比对、删除在 Redis 内一次完成
        lockReleaseSupport.releaseIfOwned(LOCK_KEY, token, "审核任务清扫");
    }
}
