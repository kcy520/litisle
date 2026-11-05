package com.litisle.litislesearch.job;

import com.litisle.litislesearch.metrics.SearchMetrics;
import com.litisle.litislesearch.service.HotQueryCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 热点查询缓存合并失效任务（批次九优化一：失效降频）。
 *
 * 【背景】索引同步原先每条事件都执行 evictAll 全量 SCAN，事件频度上升时 SCAN 次数线性放大、
 * 缓存被反复清空；改为事件只打脏标记，本任务按固定间隔合并清一次。
 *
 * 【异常】Redis/失效失败只记日志与指标，不向上抛出：定时任务没有 MQ 重投通道，
 * 下一轮会因脏标记仍存在（或已尽力补打）继续尝试，保证最终一致。
 *
 * 【隐私红线】下架事件不走本任务合并窗口，由 IndexSyncConsumer 立即 evictAll。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotQueryCacheEvictJob {

    /** 热点查询缓存服务（本任务只调用 evictIfDirty，避免重复实现 SCAN 逻辑） */
    private final HotQueryCacheService hotQueryCacheService;

    /** 观测指标（合并失效结果计数，Micrometer 未装配时静默空转） */
    private final SearchMetrics searchMetrics;

    /**
     * 按配置间隔检查脏标记并合并失效（默认 1 秒，可由 yml/Nacos 调整）。
     * <p>fixedDelay 语义：上一轮结束后再等待间隔，避免清理耗时叠加导致任务堆积。</p>
     */
    @Scheduled(fixedDelayString = "${litisle.search.cache.evict-merge-interval-ms:1000}")
    public void mergeEvict() {
        try {
            // 返回 false 表示本轮无脏标记（无需清理），不计 success 指标，避免空闲期虚增
            boolean executed = hotQueryCacheService.evictIfDirty();
            if (executed) {
                searchMetrics.recordCacheEvictMerge(true);
            }
        } catch (Exception e) {
            // 合并失效失败：记失败指标与 error 日志，脏标记已由服务尽力保留，下轮继续
            searchMetrics.recordCacheEvictMerge(false);
            log.error("[热点查询缓存] 合并失效失败，下一轮继续", e);
        }
    }
}
