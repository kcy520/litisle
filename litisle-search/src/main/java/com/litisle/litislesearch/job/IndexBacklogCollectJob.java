package com.litisle.litislesearch.job;

import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litislesearch.metrics.SearchMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 索引同步队列积压采集任务（批次九 9.9-F）。
 *
 * IndexBacklogCollectJob 是一个 可观测性采集任务 ：每 15 秒去 MQ 问一次「search 的 9 个业务队列各还剩多少条消息没消费」，把数字写进监控指标，供告警和扩容决策用。 它不消费、不处理任何消息，纯粹是“看一眼、记一笔”。
 * 原有指标（`litisle_search_consume_total` ）只能表达「消费了多少」，表达不了「还欠着多少」。没有积压指标时，如果消费者挂了或消费变慢，你只能事后从日志发现——而不是在监控面板上看到积压曲线持续上涨。本任务补的就是这个缺口：
 *   - 指标：`litisle_search_index_backlog{queue}` （gauge 类型，记录每个队列的 ready 消息数）
 *   - 用途：支撑「 积压持续 >5 分钟 」的告警；也是判断「要不要调大消费并发度 / 扩容」的依据
 * 
 * 【目的】现有指标只能表达消费吞吐（litisle_search_consume_total{queue,result}），
 * 无法表达「还有多少消息没消费」；本任务定时读取队列消息数并写入 gauge
 * litisle_search_index_backlog{queue}，支撑「积压持续 >5 分钟」告警，
 * 也作为并发度调整/扩容方案的触发判断依据。
 *
 * 【采集口径】覆盖 search 实际监听的全部 9 个业务队列（文章/互动/用户三条链路，
 * 与 IndexSyncConsumer、HeatEventConsumer 的 @RabbitListener 逐一核对），
 * 队列名统一取 MqConstant 常量（拓扑声明与实际队列同名，禁字面量硬编码）。
 *
 * 【调度隔离】采集交给独立单线程执行器，调度线程只负责提交后立即返回：
 * MQ 卡顿（如连接超时）只会占住采集线程，不会拖住默认调度线程上的热度刷写与缓存合并任务；
 * 配合 fixedDelay 语义与 CAS 重入标记，上一轮未结束时直接跳过本轮，任务不会堆积。
 *
 * 【容错】单队列读取失败只 WARN 跳过该队列，其余队列照常采集；
 * 整体异常由兜底 try/catch 记录 error 日志，绝不上抛（定时任务无 MQ 重投通道，下一轮自然重试）。
 *
 * @author litisle
 */
@Slf4j
@Component
public class IndexBacklogCollectJob {

//     ## 工作流程
// 每 15s（fixedDelay，启动后延迟 15s 避开建队阶段）
//   └─ collectBacklog()：CAS 抢占标记 → 提交到独立线程 → 立即返回
//        └─ collectOnce()：遍历 9 个队列
//             └─ amqpAdmin.getQueueInfo(队列名) → 取 messageCount → 写入 gauge

    /**
     * 需要采集积压的队列清单：search 模块实际监听的全部 9 个业务队列
     * （IndexSyncConsumer 6 个 + HeatEventConsumer 3 个，与 @RabbitListener 声明逐一核对）。
     * <p>文章链路：发布 / 下架 / 元数据更新 / 浏览量落库 / 书籍变更；
     * 互动链路：点赞 / 评论；用户链路：注册 / 资料更新。</p>
     * <p>不含死信队列（litisle.search.index.dlq.queue）：死信已有
     * litisle_search_index_dlq_total{originQueue} 计数器与独立告警，重复采集会形成两套口径。</p>
     * <p>队列名统一取 MqConstant 常量（与拓扑声明同名，禁字面量），标签基数固定为 9 个，不会膨胀。</p>
     */
    private static final List<String> BACKLOG_QUEUES = List.of(
            // 文章发布（upsert article_idx + 向量 + chunks）
            MqConstant.QUEUE_CONTENT_PUBLISHED,
            // 内容下架（删索引 + 墓碑）
            MqConstant.QUEUE_CONTENT_UNPUBLISHED,
            // 文章元数据更新（partial update）
            MqConstant.QUEUE_CONTENT_ARTICLE_UPDATED,
            // 浏览量落库（view_cnt + heat 重算）
            MqConstant.QUEUE_CONTENT_VIEW_COUNT_FLUSHED,
            // 书籍索引变更（upsert/删除 book_idx）
            MqConstant.QUEUE_CONTENT_BOOK_CHANGED,
            // 点赞事件（like_cnt + heat 重算）
            MqConstant.QUEUE_SOCIAL_LIKED,
            // 评论事件（comment_cnt + heat 重算）
            MqConstant.QUEUE_SOCIAL_COMMENTED,
            // 用户注册索引同步
            MqConstant.QUEUE_USER_REGISTERED_INDEX,
            // 用户资料更新索引同步
            MqConstant.QUEUE_USER_PROFILE_UPDATED_INDEX);

    /** MQ 管理接口（读取队列消息数；容器中已由 RabbitAutoConfiguration 提供 AmqpAdmin 实现） */
    private final AmqpAdmin amqpAdmin;

    /** 观测指标埋点（把采集结果写入 litisle_search_index_backlog{queue} gauge） */
    private final SearchMetrics searchMetrics;

    /** 采集线程池（单线程，与 Spring 默认调度线程隔离，防 MQ 卡顿阻塞其他定时任务） */
    private final ExecutorService collectExecutor;

    /** 采集进行中标记（true=上一轮尚未结束，本轮直接跳过，防任务堆积） */
    private final AtomicBoolean collecting = new AtomicBoolean(false);

    /**
     * 构造采集任务并初始化专用单线程执行器。
     *
     * @param amqpAdmin    MQ 管理接口（读取队列消息数）
     * @param searchMetrics 观测指标埋点
     */
    public IndexBacklogCollectJob(AmqpAdmin amqpAdmin, SearchMetrics searchMetrics) {
        this.amqpAdmin = amqpAdmin;
        this.searchMetrics = searchMetrics;
        // 守护线程：不阻止 JVM 退出，命名便于线程栈/监控定位
        this.collectExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "search-index-backlog-collector");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 停机时释放采集线程池（与热度刷写任务同一范式，避免线程泄漏）。
     */
    @PreDestroy
    public void shutdown() {
        collectExecutor.shutdown();
    }

    /**
     * 定时入口：提交采集任务后立即返回（fixedDelay=上一轮提交后等待间隔，不在调度线程执行采集）。
     * <p>首次延迟避开启动阶段队列声明与 MQ 连接建立，周期取 yml
     * litisle.search.metrics.backlog-collect-interval-ms（默认 15s）。</p>
     */
    @Scheduled(fixedDelayString = "${litisle.search.metrics.backlog-collect-interval-ms:15000}",
            initialDelayString = "${litisle.search.metrics.backlog-collect-initial-delay-ms:15000}")
    public void collectBacklog() {
        // CAS 抢占失败：上一轮采集尚未结束（MQ 响应慢/卡顿），本轮跳过，避免任务排队堆积
        if (!collecting.compareAndSet(false, true)) {
            log.debug("[索引积压采集] 上一轮采集尚未结束，跳过本轮");
            return;
        }
        try {
            collectExecutor.execute(() -> {
                try {
                    // 实际采集在独立线程执行，单队列异常已在 collectOnce 内部消化
                    collectOnce();
                } catch (Exception e) {
                    // 兜底：MQ 不可用等整体异常只记录日志不上抛，下一轮继续
                    log.error("[索引积压采集] 本轮整体异常，跳过（下一轮继续采集）", e);
                } finally {
                    // 无论成功失败都释放标记，保证后续轮次可以继续
                    collecting.set(false);
                }
            });
        } catch (Exception e) {
            // 线程池拒绝（如停机中）：释放标记并留痕，避免标记永久占用
            collecting.set(false);
            log.error("[索引积压采集] 采集任务提交失败，本轮跳过", e);
        }
    }

    /**
     * 逐队列读取当前消息数并写入指标。
     * <p>单队列异常只影响该队列：记录带队列名的 WARN 日志后继续采集下一个，
     * 保证某个队列无权限/不存在时不拖累整轮。</p>
     */
    private void collectOnce() {
        for (String queueName : BACKLOG_QUEUES) {
            try {
                // 读取队列信息；队列不存在或无权限时返回 null（部分 broker 版本直接抛异常）
                QueueInformation queueInfo = amqpAdmin.getQueueInfo(queueName);
                if (queueInfo == null) {
                    log.warn("[索引积压采集] 队列不存在或无权限读取，跳过该队列本轮采集：queue={}", queueName);
                    continue;
                }
                // 当前积压消息数（ready 消息数，不含未确认消息）
                long messageCount = queueInfo.getMessageCount();
                searchMetrics.recordIndexBacklog(queueName, messageCount);
                log.debug("[索引积压采集] queue={}, messageCount={}", queueName, messageCount);
            } catch (Exception e) {
                // 单队列失败：保留 queue 名与异常栈，便于定位是连接问题还是权限问题
                log.warn("[索引积压采集] 读取队列消息数失败，跳过该队列本轮采集：queue={}", queueName, e);
            }
        }
    }
}
