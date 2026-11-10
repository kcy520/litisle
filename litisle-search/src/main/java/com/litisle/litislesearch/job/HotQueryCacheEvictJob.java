package com.litisle.litislesearch.job;

import com.litisle.litislesearch.metrics.SearchMetrics;
import com.litisle.litislesearch.service.HotQueryCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.cache.CacheProperties.Redis;
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

// 它是 热点查询缓存的「合并失效」定时任务 ，解决的是一个「失效风暴」问题：

// - 改之前 ：索引每收到一条变更事件（发布/更新/书籍/用户……）就立即`evictAll` 全量 SCAN 删除缓存——事件频度上升时，SCAN 次数随事件数线性放大，且缓存被反复清空（刚缓存的查询 100ms 后又被清了）
// - 改之后 ：事件端只调用`markDirty()` 打一个脏标记（一次 Redis SET），本任务每秒检查一次， 把 1 秒内发生的 N 条事件合并成最多 1 次全量失效


// 空闲时的开销 ：看`evictIfDirty()` 的名字——它先查脏标记`search:hotquery-dirty` 是否存在。没有脏标记时，每秒只是 1 次 Redis GET （O(1) 微秒级），没有任何 SCAN。空闲期它什么都不做。
// 有脏标记时的开销 ：最多每秒 1 次 SCAN+DEL。这正是设计目的——把失效频率从「无上限（随事件数）」压到「有上限（每秒最多 1 次）」。

// ## 改造前的问题：每条事件都 evictAll 全量 SCAN
// SCAN 是什么 ：Redis 的`SCAN` 命令用于遍历匹配模式的所有键。热点查询缓存的键是`search:hotquery:{类型}:{排序}:{页码}:{条数}:{查询指纹}` ，查询指纹因关键词/筛选条件而各不相同——所以 没法用单个键删除 ，全量失效只能`SCAN search:hotquery:*` 把所有匹配键一个个找出来再删。
// 「线性放大」是什么意思 ：改造前，索引同步每消费 1 条事件就调 1 次`evictAll` 。假设热门时段每秒产生 20 条索引变更事件（发布、更新、点赞刷热度、书籍编辑……），Redis 每秒就要执行 20 次全量 SCAN。事件量翻倍，SCAN 也翻倍——这就是「SCAN 次数与事件数线性相关」。
// 「缓存被反复清空」 ：更糟的是这些 SCAN 几乎每次删的都是同一批键。一个刚被写入的缓存条目，可能 100ms 后就被下一条事件的 evictAll 清掉了——缓存命中率被自己人打穿，等于白缓存。

// ## 改造后：脏标记 + 定时合并
// 现在事件端只做一件极轻的事：`markDirty()` 打一个`search:hotquery-dirty` 脏标记（一次 SET，O(1)）。真正的 SCAN 交给本任务：

// 时间轴（旧方案）：           时间轴（新方案，间隔 1s）：
// 事件1 ──→ SCAN 清空          事件1 ──→ SET 脏标记
// 事件2 ──→ SCAN 清空          事件2 ──→ SET 脏标记（已有，无变化）
// 事件3 ──→ SCAN 清空          事件3 ──→ SET 脏标记
// ...(1秒内20条事件)           任务每秒醒来 1 次：看到脏标记 → SCAN 清空 1 次 → 清掉标记
// = 20 次 SCAN/秒              = 1 次 SCAN/秒

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
