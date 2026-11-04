package com.litisle.litislecontent.job;


import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.litislecontent.service.ArticlePublishService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 文章定时发布任务（项2 2026-11-02）。
 *
 * 作用：
 * 按配置周期（默认 1min）扫描"到点的草稿"（status=0 且 scheduled_publish_time≤now 且未软删），
 * 逐篇复用 ArticlePublishService#publishDueScheduled 内部的批量送审链路
 * （归属校验→状态机→CAS 0→1→事务提交后发 content.submitted），实现"攒稿定时发"。
 * 发布成功或失败均清空定时字段（失败 warn 留痕不静默，防同一篇被反复重扫）。
 * 范式对齐 FeedHotRankRefillJob：@Scheduled + enabled 开关 + fail-safe 吞异常（下轮重试）；
 * 多实例部署时通过分布式锁抢锁保证单点扫描（防并发互踩，抢不到直接放弃本轮）；
 * 单篇 CAS 0→1 幂等兜底（即使两实例先后拿到锁也不会重复送审）。
 *
 * @author litisle
 */
@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class ArticleScheduledPublishJob {

    /** 发布编排域服务（到点扫描 + 逐篇送审 + 清定时字段统一收口） */
    private final ArticlePublishService publishService;

    /** 分布式锁（多实例单点扫描） */
    private final DistributedLock distributedLock;

    /** 扫描锁键：多实例误开开关时防并发扫描互踩 */
    private static final String SCHEDULED_PUBLISH_LOCK_KEY = CacheConstant.LOCK_PREFIX + "content:article:scheduled-publish";

    /** 任务开关（默认开；多实例部署时分布式锁保证单点执行，也可只开一台再关其余） */
    @Value("${litisle.content.publish.scheduled.enabled:true}")
    private boolean enabled;

    /**
     * 定时扫描到点草稿并送审。
     *
     * 作用：按配置周期调 publishDueScheduled（捞到点草稿→逐篇走既有送审链路→清定时字段）；
     * 受 publish.scheduled.enabled 开关控制；抢不到扫描锁（他实例在扫）直接放弃本轮；
     * 内部逐篇独立事务 + fail-safe（单篇失败清定时留痕，批次异常吞掉下轮重试）。
     */
    @Scheduled(fixedDelayString = "${litisle.content.publish.scheduled.interval-ms:60000}",
            initialDelayString = "${litisle.content.publish.scheduled.initial-delay-ms:60000}")
    public void publishDue() {
        // 步骤1：开关判断
        if (!enabled) {
            return;
        }
        // 步骤2：抢扫描锁（wait=0 不等待：抢不到说明他实例本轮已在扫，直接放弃，fixedDelay 不堆积）
        if (!distributedLock.tryLock(SCHEDULED_PUBLISH_LOCK_KEY, 0, TimeUnit.SECONDS)) {
            return;
        }
        try {
            // 步骤3：到点扫描编排（内部逐篇独立事务，单篇失败不影响其他篇）
            int count = publishService.publishDueScheduled();
            // 步骤4：结果留痕（空轮静默，有送审才记日志）
            if (count > 0) {
                log.info("定时发布扫描完成：本轮送审 {} 篇", count);
            }
        } catch (Exception e) {
            // 兜底 fail-safe：本轮异常不影响下轮（到点草稿下轮会再次被捞出）
            log.warn("定时发布任务异常（fail-safe 下轮重试）", e);
        } finally {
            distributedLock.unlock(SCHEDULED_PUBLISH_LOCK_KEY);
        }
    }
}
