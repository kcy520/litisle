package com.litisle.litislecontent.job;


import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.litislecontent.service.support.FeedHotRankSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 推荐流热度榜灌榜定时任务（阶段14 B2；P1 增量优化 2026-10-28）。
 *
 * 作用：
 * 按配置周期（默认 5min，启动延迟 1min）调 FeedHotRankSupport#refillAuto 自动编排灌榜：
 * 高频增量轮按增量水位只捞 update_time 变更文章重算 ZADD（DB 压力随变更量而非存量走），
 * 低频全量轮（默认 1h，fullRefillIntervalMs）全量重算兜底分数衰减收敛、
 * 榜缺失时全量重建；经影子键 rename 原子换榜（灌榜期间读方无空榜窗口）。
 * 范式对齐 ViewCountFlushJob：@Scheduled + enabled 开关 + fail-safe 吞异常（下轮重试）；
 * 多实例部署时通过分布式锁抢锁保证单点灌榜（防并发互踩，抢不到直接放弃本轮）。
 *
 * 【social 落地后无需返工】like/comment 计数回写进 article 表会刷新 update_time
 * （DB ON UPDATE CURRENT_TIMESTAMP），下一轮增量灌榜自动捞到；实时性增强
 * （回写后即时增量重算）见 ContentInternalServiceImpl#updateCounters 的 D10 TODO 接线点。
 *
 * @author litisle
 */
@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class FeedHotRankRefillJob {

    /** 热度榜支撑（全量/增量灌榜编排 + ZSet 灌榜/换榜/裁剪） */
    private final FeedHotRankSupport feedHotRankSupport;

    /** 分布式锁（多实例单点灌榜） */
    private final DistributedLock distributedLock;

    /** 灌榜锁键：多实例误开开关时防并发灌榜互踩 */
    private static final String REFILL_LOCK_KEY = CacheConstant.LOCK_PREFIX + "content:feed:hot:refill";

    /** 灌榜开关（默认开；多实例部署时分布式锁保证单点执行，也可只开一台再关其余） */
    @Value("${litisle.content.feed.hot.refill-enabled:true}")
    private boolean enabled;

    /**
     * 定时灌榜。
     *
     * 作用：按配置周期调 refillAuto 编排（增量轮为主、低频全量校正、榜缺失全量重建）；
     * 受 feed.hot.refill-enabled 开关控制；抢不到灌榜锁（他实例在灌）直接放弃本轮；
     * Support 内部失败保留旧榜与水位（fail-safe，下轮重试）。
     */
    @Scheduled(fixedDelayString = "${litisle.content.feed.hot.refill-interval-ms:300000}",
            initialDelayString = "${litisle.content.feed.hot.refill-initial-delay-ms:60000}")
    public void refill() {
        // 步骤1：开关判断
        if (!enabled) {
            return;
        }
        // 步骤2：抢灌榜锁（wait=0 不等待：抢不到说明他实例本轮已在灌，直接放弃，fixedDelay 不堆积）
        if (!distributedLock.tryLock(REFILL_LOCK_KEY, 0, TimeUnit.SECONDS)) {
            return;
        }
        try {
            // 步骤3：灌榜编排（P1 增量优化 2026-10-28：refillAuto 按榜/水位状态自动选择
            // 全量重建/低频全量校正/水位增量 ZADD，内部已 fail-safe，失败保留旧榜）
            int count = feedHotRankSupport.refillAuto();
            // 步骤4：结果留痕（空轮静默，有入榜才记日志）
            if (count > 0) {
                log.info("热度榜灌榜完成：articles={}", count);
            }
        } catch (Exception e) {
            // 兜底 fail-safe：本轮异常不影响下轮（refillHotRank 内部已保旧榜）
            log.warn("热度榜灌榜任务异常（fail-safe 下轮重试）", e);
        } finally {
            distributedLock.unlock(REFILL_LOCK_KEY);
        }
    }
}
