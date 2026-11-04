package com.litisle.litislecontent.service.support;

import com.litisle.litislecontent.domain.vo.FeedItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Feed 本地兜底支撑（批次E P4 定案 D-4 2026-11-03）。
 *
 * 作用：
 * 兜住「Redis 全宕时单飞锁失效、N 并发直查 DB」的场景 C——不引第三方本地缓存依赖，
 * 按 cacheKey 粒度手写轻量实现：
 * ① JVM Semaphore 本地单飞：每 cacheKey 单许可，同一 key 同一时刻仅 1 线程回源 DB，
 *    其余线程等待（等待方取 last-good 快照或返回空，绝不放大 DB 并发——本地闸门即限流收口）；
 * ② ConcurrentHashMap last-good 快照：Redis 正常期间每次回源成功都刷新，
 *    Redis 故障期作为兜底数据源（数据可能稍旧，展示前回查仍会过滤不可见条目）。
 *
 * 内存占用可控性：feed 的 cacheKey 组合有限（page×size），且快照/闸门表都有条目上限——
 * 超上限（恶意深翻页等异常 key 量）不再新增条目，仅日志留痕，主流程不受影响。
 *
 * @author litisle
 */
@Slf4j
@Component
public class FeedLocalFallbackSupport {

    /** 快照表条目上限：feed key 组合有限（page×size），防恶意深翻页刷出无限 key 撑爆内存 */
    private static final int MAX_TRACKED_KEYS = 500;

    /** 快照最大年龄（毫秒）：超过视为过期（返回空走单飞回源），防长期服务陈旧数据 */
    private static final long SNAPSHOT_MAX_AGE_MS = 10 * 60 * 1000L;

    /** 每 cacheKey 的本地单飞闸门（单许可，JVM 内互斥；key 数量与快照表同步增长） */
    private final ConcurrentHashMap<String, Semaphore> keyGates = new ConcurrentHashMap<>();

    /** 每 cacheKey 的 last-good 快照（值含落快照时间戳，过期判断用） */
    private final ConcurrentHashMap<String, SnapshotEntry> lastGoodCache = new ConcurrentHashMap<>();

    /** 溢出兜底闸门：key 数量达上限后的共用闸门（牺牲并发换内存安全，异常场景下的保底限流） */
    private final Semaphore overflowGate = new Semaphore(1);

    /**
     * 快照条目（last-good 数据 + 落快照时间戳，用于过期判断）
     */
    private record SnapshotEntry(List<FeedItemVO> items, long savedAtMillis) {
    }

    /**
     * 取指定 cacheKey 的本地单飞闸门（不存在则创建，单许可）
     *“本地” = JVM 本地内存里做的，不依赖任何外部组件。
     *
     * 给每一个缓存 key 分配一个“门禁”，保证同一个 key 在同一时间只有一个线程能去查数据库。
     * 如果 key 太多，超出的 key 共用一个"溢出闸门"，防止内存被撑爆。
     *
     * @param cacheKey 结果缓存键
     * @return JVM 内单许可信号量
     */
    public Semaphore gate(String cacheKey) {

        //① 先看有没有现成的闸门
        // 已有 key 直接复用；新 key 且已达上限 → 共用溢出闸门（防恶意深翻页刷 key 撑爆内存）
        Semaphore existing = keyGates.get(cacheKey); //如果已经有这个 key 的门禁了，直接复用，不再新建。
        if (existing != null) {
            return existing;
        }
        //② 如果 Map 已经满了(设置上限，防止内存问题)，怎么办？
        if (keyGates.size() >= MAX_TRACKED_KEYS) {
            //超过上限后：不再新建闸门，所有“新 key”共用一个 overflowGate
            log.warn("本地兜底闸门表已达上限（{}），key 共用溢出闸门：{}", MAX_TRACKED_KEYS, cacheKey);
            return overflowGate;
        }

        //③ 正常情况：给这个 key 新建一个闸门
        // computeIfAbsent 保证并发下同 key 只建一个闸门
          //溢出 key 共用一个闸门 → 第一次请求串行回源 DB 并缓存快照 → 后续请求直接读快照不查 DB → Redis 恢复后整条降级链路不再触发。
            //快照就是一个 Map<cacheKey, 一页数据>，每个 key 存自己的、读自己的，不存在跨 key 共用。溢出 key 共用的是"闸门"（限流），不是"快照"（数据）。双检快照是为了让同一个 key 的后续并发请求不重复查 DB。
        return keyGates.computeIfAbsent(cacheKey, k -> new Semaphore(1));
    }

    /**
     * 取指定 cacheKey 的 last-good 快照（过期返回 null，视为无快照）
     *
     * @param cacheKey 结果缓存键
     * @return 快照数据；无快照或已过期返回 null
     */
    public List<FeedItemVO> snapshot(String cacheKey) {
        // 取快照并校验年龄：过期（超过 SNAPSHOT_MAX_AGE_MS）视为无快照，避免长期服务陈旧数据
        SnapshotEntry entry = lastGoodCache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.savedAtMillis() > SNAPSHOT_MAX_AGE_MS) {
            // 过期清理（顺带释放内存）；返回 null 让调用方走单飞回源
            lastGoodCache.remove(cacheKey, entry);
            return null;
        }
        return entry.items();
    }

    /**
     * 落 last-good 快照（Redis 正常链路每次回源成功后调用，保持快照近似最新）
     *
     * lastGoodCache 是一个 JVM 内存里的 Map，存的是"上一次成功从数据库查出来的那一页 feed 数据"。
     *
     * @param cacheKey 结果缓存键
     * @param items    回源所得条目
     */
    public void save(String cacheKey, List<FeedItemVO> items) {
        // 条目上限保护：key 数量异常增长（恶意深翻页）时不再新增，仅留痕（既有 key 正常刷新不受影响）
        if (!lastGoodCache.containsKey(cacheKey) && lastGoodCache.size() >= MAX_TRACKED_KEYS) {
            log.warn("本地兜底快照表已达上限（{}），跳过新增 key：{}", MAX_TRACKED_KEYS, cacheKey);
            return;
        }
        // 覆盖写（同 key 新值顶旧值）；items 可能为空列表（防穿透空结果），同样可作兜底数据
        lastGoodCache.put(cacheKey, new SnapshotEntry(items, System.currentTimeMillis()));
    }
}
