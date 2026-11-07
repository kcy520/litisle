package com.litisle.litislecontent.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * content 模块观测指标埋点（批次十收尾：推荐流指标接入 Micrometer）
 *
 * <p>命名与接入风格对齐 search 模块 {@code SearchMetrics}：本类只依赖 {@link ObjectProvider}
 * 获取 {@link MeterRegistry}，未装配时全方法静默空转（观测是增强件，缺指标不得阻断推荐流主流程）。</p>
 *
 * 【指标清单（Micrometer 注册名 → Prometheus 暴露名）】
 * <ul>
 *   <li>{@code litisle_content_feed_degraded} → {@code litisle_content_feed_degraded_total}：
 *   Counter，标签 {@code stream=article|book}、{@code reason=redis-down|last-good|hot-empty|build-failed}，
 *   由 {@code FeedStreamServiceImpl#logDegrade} 单入口打点，保证「降级必经此处」</li>
 *   <li>{@code litisle_content_feed_snapshot_created} → {@code litisle_content_feed_snapshot_created_total}：
 *   Counter，标签 {@code stream=article|book}、{@code cold_start=true|false}，
 *   在文章流/书籍流快照成功落 Redis 后打点</li>
 *   <li>{@code litisle_content_feed_profile_cache_hit} → {@code litisle_content_feed_profile_cache_hit_total}：
 *   Counter，无标签，画像缓存命中次数</li>
 *   <li>{@code litisle_content_feed_profile_cache_miss} → {@code litisle_content_feed_profile_cache_miss_total}：
 *   Counter，无标签，画像缓存未命中次数（未命中后回源聚合）</li>
 *   <li>{@code litisle_content_stat_reconcile_diff} → {@code litisle_content_stat_reconcile_diff}：
 *   Gauge，标签 {@code type=article_collect|article_view|article_like|article_comment|book_collect|book_reader_under}，
 *   表示「最近一轮对账的差异行数」，0 也要暴露（无数据与一切正常必须可区分）；6 条在启动时预注册，见 {@link #registerStatDiffGauges()}</li>
 * </ul>
 *
 * <p>降级/快照创建/画像缓存/写失败均为累计事件，用 Counter；
 * 对账差异是「当前状态量」而非累计事件，改用 Gauge（每次对账 set 覆盖为最新值，0 也写入）。
 * Micrometer 同名 meter 只注册一次，Gauge 的数值载体（{@link AtomicLong}）由本类长期持有，
 * 6 种 type 在 {@code @PostConstruct} 一次性注册固定标签组合，避免运行时动态建标签导致注册失败。</p>
 *
 * <p>标签基数纪律：{@code stream}/{@code reason}/{@code cold_start} 均为有限枚举值，
 * 取值统一来自 {@link com.litisle.litislecontent.constant.FeedConstant} 与本类常量，
 * 禁止在调用点拼接用户输入或动态字符串，防 Prometheus 时间线基数膨胀。</p>
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class ContentMetrics {

    /** 指标名：推荐流降级次数（标签 stream/reason；Prometheus 暴露名自动补 _total） */
    private static final String METRIC_FEED_DEGRADED = "litisle_content_feed_degraded";

    /** 指标名：推荐流快照创建次数（标签 stream/cold_start；Prometheus 暴露名自动补 _total） */
    private static final String METRIC_FEED_SNAPSHOT_CREATED = "litisle_content_feed_snapshot_created";

    /** 指标名：画像缓存命中次数（Prometheus 暴露名自动补 _total） */
    private static final String METRIC_PROFILE_CACHE_HIT = "litisle_content_feed_profile_cache_hit";

    /** 指标名：画像缓存未命中次数（Prometheus 暴露名自动补 _total） */
    private static final String METRIC_PROFILE_CACHE_MISS = "litisle_content_feed_profile_cache_miss";

    /** 指标名：互动统计对账差异行数（Gauge，标签 type=差异类型；批次十一 11.16） */
    private static final String METRIC_STAT_RECONCILE_DIFF = "litisle_content_stat_reconcile_diff";

    /** 指标名：曝光写库失败次数（Counter，标签 stream；批次十一 11.6，只记失败不记 CTR） */
    private static final String METRIC_FEED_EXPOSURE_WRITE_FAIL = "litisle_content_feed_exposure_write_fail";

    /** 指标名：点击写库失败次数（Counter，标签 stream；批次十一 11.6，只记失败不记 CTR） */
    private static final String METRIC_FEED_CLICK_WRITE_FAIL = "litisle_content_feed_click_write_fail";

    /** 对账差异类型：文章收藏数（统计表 vs favorite 聚合） */
    public static final String STAT_TYPE_ARTICLE_COLLECT = "article_collect";

    /** 对账差异类型：文章浏览量（统计表 vs article.view_cnt） */
    public static final String STAT_TYPE_ARTICLE_VIEW = "article_view";

    /** 对账差异类型：文章点赞数（统计表 vs article.like_cnt） */
    public static final String STAT_TYPE_ARTICLE_LIKE = "article_like";

    /** 对账差异类型：文章评论数（统计表 vs article.comment_cnt） */
    public static final String STAT_TYPE_ARTICLE_COMMENT = "article_comment";

    /** 对账差异类型：书籍收藏数（统计表 vs favorite 聚合） */
    public static final String STAT_TYPE_BOOK_COLLECT = "book_collect";

    /** 对账差异类型：书籍阅读人数少计（reading_history 聚合 > book_stat.reader_count） */
    public static final String STAT_TYPE_BOOK_READER_UNDER = "book_reader_under";

    /** 标签名：对账差异类型（取值来自本类 STAT_TYPE_* 常量，有限枚举） */
    private static final String TAG_STAT_TYPE = "type";

    /** 标签名：流类型（article/book，取值来自 FeedConstant.STREAM_*） */
    private static final String TAG_STREAM = "stream";

    /** 标签名：降级原因（redis-down/last-good/hot-empty/build-failed，取值来自 FeedConstant.DEGRADED_*） */
    private static final String TAG_REASON = "reason";

    /** 标签名：是否冷启动（true/false） */
    private static final String TAG_COLD_START = "cold_start";

    /** 冷启动标签值：是（无画像/新用户，两路个性化跳过） */
    private static final String COLD_START_TRUE = "true";

    /** 冷启动标签值：否（有近期阅读行为，画像参与打分） */
    private static final String COLD_START_FALSE = "false";

    /** Micrometer 注册表提供器（可能不存在，取不到时指标静默跳过） */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /** 对账差异类型全量清单（预注册用，顺序固定便于排查；取值必须与 Job 实际上报的 type 一致） */
    private static final String[] STAT_DIFF_TYPES = {
            STAT_TYPE_ARTICLE_COLLECT,
            STAT_TYPE_ARTICLE_VIEW,
            STAT_TYPE_ARTICLE_LIKE,
            STAT_TYPE_ARTICLE_COMMENT,
            STAT_TYPE_BOOK_COLLECT,
            STAT_TYPE_BOOK_READER_UNDER
    };

    /** 各差异类型当前值的数值载体（Gauge 只持有弱引用，这里强持有防被回收） */
    private final Map<String, AtomicLong> statDiffValues = new LinkedHashMap<>();

    /** 预注册标记：保证 6 条 Gauge 只注册一次（重复 register 会返回旧 meter，数值载体不再生效） */
    private final AtomicBoolean statDiffRegistered = new AtomicBoolean(false);

    /**
     * 启动时预注册 6 类对账差异 Gauge（批次十一 11.16 修复）。
     *
     * <p>目的：差异为 0 时指标也必须在 Prometheus 存在且显示 0，
     * 让「没有对账数据」与「对账一切正常」可以区分。</p>
     *
     * <p>用 Gauge 而不是 Counter：差异行数是「最近一轮的状态量」，
     * Counter 只能累计历史差异，无法表达当前是否已恢复一致。</p>
     */
    @PostConstruct
    public void registerStatDiffGauges() {
        // 没有注册表（例如单元测试/未启用 actuator）时静默跳过，不影响主流程
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        // 只允许注册一次，避免重复创建 meter 时数值载体被丢弃
        if (!statDiffRegistered.compareAndSet(false, true)) {
            return;
        }
        for (String type : STAT_DIFF_TYPES) {
            // 先取/建数值载体，再注册 Gauge（标签组合固定，不做运行时动态标签）
            AtomicLong value = statDiffValues.computeIfAbsent(type, key -> new AtomicLong());
            Gauge.builder(METRIC_STAT_RECONCILE_DIFF, value, AtomicLong::doubleValue)
                    .description("最近一轮互动统计对账的差异行数（0 表示一致）")
                    .tags(TAG_STAT_TYPE, type)
                    .register(registry);
        }
    }

    /**
     * 记录一次推荐流降级（必须在 {@code logDegrade} 内调用，保持降级单入口留痕）
     *
     * @param streamType 流类型（FeedConstant.STREAM_ARTICLE / STREAM_BOOK）
     * @param reason     降级原因（FeedConstant.DEGRADED_*，有限枚举值）
     */
    public void recordFeedDegraded(String streamType, String reason) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_FEED_DEGRADED, TAG_STREAM, streamType, TAG_REASON, reason).increment();
    }

    /**
     * 记录一次推荐流快照创建（快照成功落 Redis 后调用；空池快照同样计入）
     *
     * @param streamType 流类型（FeedConstant.STREAM_ARTICLE / STREAM_BOOK）
     * @param coldStart  是否冷启动（true=无画像/新用户，探索位比例已上调）
     */
    public void recordFeedSnapshotCreated(String streamType, boolean coldStart) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_FEED_SNAPSHOT_CREATED,
                TAG_STREAM, streamType,
                TAG_COLD_START, coldStart ? COLD_START_TRUE : COLD_START_FALSE).increment();
    }

    /**
     * 记录一次画像缓存命中（命中时直接复用缓存，不回源 reading_history）
     */
    public void recordProfileCacheHit() {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_PROFILE_CACHE_HIT).increment();
    }

    /**
     * 记录一次画像缓存未命中（未命中后回源聚合，并覆盖写缓存）
     */
    public void recordProfileCacheMiss() {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_PROFILE_CACHE_MISS).increment();
    }

    /**
     * 记录一轮互动统计对账的差异行数（批次十一 11.16）。
     *
     * <p>6 类 type 在启动时已预注册为 Gauge；本方法每次把「最近一轮的值」写入，
     * 差异为 0 时同样写入 0（覆盖旧值），保证 Prometheus 永远能看到 6 条时间线。</p>
     *
     * @param type      差异类型（本类 STAT_TYPE_* 常量，有限枚举值）
     * @param diffRows  本次对账发现的差异行数（负数按 0 处理）
     */
    public void recordStatReconcileDiff(String type, long diffRows) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        // 兜底：若 @PostConstruct 早于注册表装配（个别测试场景），首次上报时补注册
        registerStatDiffGauges();
        // 只接受预注册的 6 类，未知 type 直接忽略，避免标签基数膨胀
        AtomicLong value = statDiffValues.get(type);
        if (value == null) {
            return;
        }
        // 覆盖为最近一轮的值：0 也要写，否则 Prometheus 会残留上一轮的差异数
        value.set(Math.max(0L, diffRows));
    }

    /**
     * 记录一次曝光写库失败（批次十一 11.6）。
     *
     * <p>只统计「写失败」这一异常事件，不为 CTR 本身建 Gauge——CTR 由日聚合表查询得出，
     * 属于报表口径而不是运行时观测口径。</p>
     *
     * @param streamType 流类型（FeedConstant.STREAM_ARTICLE / STREAM_BOOK）
     */
    public void recordFeedExposureWriteFailure(String streamType) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_FEED_EXPOSURE_WRITE_FAIL, TAG_STREAM, streamType).increment();
    }

    /**
     * 记录一次点击写库失败（批次十一 11.6）。
     *
     * <p>失败只告警不阻断详情返回；与曝光失败分开计数，便于区分是翻页链路还是详情链路异常。</p>
     *
     * @param streamType 流类型（FeedConstant.STREAM_ARTICLE / STREAM_BOOK）
     */
    public void recordFeedClickWriteFailure(String streamType) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_FEED_CLICK_WRITE_FAIL, TAG_STREAM, streamType).increment();
    }
}
