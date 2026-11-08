package com.litisle.litislereview.sweeper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litislereview.domain.entity.WordlibMisreport;
import com.litisle.litislereview.mapper.WordlibMisreportMapper;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import com.litisle.litislereview.support.RedisLockReleaseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * wordlib_misreport 误报记录 TTL 清理 sweeper（词库治理）：
 * 按外置保留期（litisle.moderation.wordlib.misreport.retention-days）清理过期上报行，
 * 防误报明细表无限增长；误报属运营数据非合规硬凭证（区别于 moderation_record ≥6 月留痕），
 * 聚合结论已沉淀在灰度转正裁决里，过期可删
 *
 * 按可配置的保留天数，每天凌晨 5 点自动删除过期的误报上报记录，防止该表无限膨胀；多实例部署时通过 Redis 互斥锁保证只有一个实例执行。
 *
 * <p>调度：天级 05:00（与 quarantine 03:00 / record 归档 04:00 / file 侧 02:00 错峰）；
 * 多实例互斥锁沿用 sweeper 包 SETNX+token 纪律（对齐 QuarantineSweeper/ReviewTaskSweeper）；
 * retention-days 非正数=保留全量（清理关闭）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WordlibMisreportCleanupSweeper {

    /** 清扫互斥锁 key（固定常量，不含任何用户输入，符合 Redis key 服务端派生纪律） */
    private static final String LOCK_KEY = "review:sweeper:wordlib-misreport-cleanup:lock";

    /** 互斥锁 TTL：天级任务 10 分钟足够跑完，超时由 TTL 自愈 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private final WordlibMisreportMapper wordlibMisreportMapper;
    private final ModerationProperties properties;
    private final StringRedisTemplate stringRedisTemplate;

    /** 互斥锁原子释放工具（Lua 比对 token 后删除，消除 GET 与 DEL 之间的误删窗口） */
    private final RedisLockReleaseSupport lockReleaseSupport;

    /**
     * 天级清理入口（05:00 与既有 sweeper 错峰；多实例互斥锁先行）
     */
    @Scheduled(cron = "0 0 5 * * ?")
    public void cleanup() {
        // 非正数=保留全量（清理关闭），直接跳过
            //从配置里读取误报记录的保留天数
        int retentionDays = properties.getWordlib().getMisreport().getRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        // ===== 步骤0：SETNX 抢互斥锁（token 防误删他锁，finally 比对归属释放） =====
        String token = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL);
        if (!Boolean.TRUE.equals(locked)) {
            // 他实例持锁中，本轮跳过
            return;
        }
        try {
            // 候选筛选：last_report_time 早于 now-retention-days（只顺延删除、宁晚勿早；
            // 重复上报刷新 last_report_time，活跃误报行不会被误删）

            //现在时间-保留天数 = 过期时间
            LocalDateTime expireBefore = LocalDateTime.now().minusDays(retentionDays);
            //调用MySQL，删除时间 < 过期时间的 误报行  （lt ：less than 小于）
            int deleted = wordlibMisreportMapper.delete(
                    new LambdaQueryWrapper<WordlibMisreport>()
                            .lt(WordlibMisreport::getLastReportTime, expireBefore));
            if (deleted > 0) {
                log.info("[误报记录清理] 本轮清理过期误报行 {} 条（retentionDays={}）", deleted, retentionDays);
            }
        } catch (Exception e) {
            // 清理失败仅告警，不影响上报主链路；下轮调度自然重试
            log.error("[误报记录清理] 本轮清理失败（下轮重试）retentionDays={}", retentionDays, e);
        } finally {
            releaseLock(token);
        }
    }

    /**
     * 释放互斥锁（Lua 原子比对 token 归属后才 DEL，防误删他实例锁；异常仅 warn 留痕由锁 TTL 自愈）
     *
     * @param token 本实例锁 token
     */
    private void releaseLock(String token) {
        // 原实现是 GET 比对后再 DEL 两步：两步之间锁若 TTL 到期被他人抢占，DEL 会误删他人的锁。
        // 改为 Lua 原子执行，取值、比对、删除在 Redis 内一次完成
        lockReleaseSupport.releaseIfOwned(LOCK_KEY, token, "[误报记录清理] ");
    }
}
