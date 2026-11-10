package com.litisle.litislesearch.constant;

/**
 * search 模块 Redis 键与字段常量（批次A A7 互动热度 + 批次四 4.1 热点查询缓存 + 下架墓碑）。
 *
 * 说明：键名遵循企业缓存键规范 search:模块:功能:唯一标识，是消费端/定时任务/缓存层之间的对齐依据，
 * 不适合外置 yml，统一收口避免字符串硬编码
 */
public final class SearchRedisConstant {

    /** 工具类禁止实例化 */
    private SearchRedisConstant() {
    }

    // ==================== 互动热度 pending 键（批次A A7：Redis 累加 + 定时 _bulk） ====================

    /**
     * 互动热度待刷新键前缀（完整键 = 前缀 + articleId，Hash 结构）：
     * 三事件消费端仅累加本键（不直写 ES），IndexHeatFlushJob 按刷写周期 GETDEL 原子取走并聚合 _bulk；
     * TTL 由 SearchProperties.Heat.Flush.pendingKeyTtl 控制（默认 2h，≥2 个刷写周期防未刷先清）
     */
    public static final String HEAT_PENDING_KEY_PREFIX = "search:heat:pending:";

    /** pending Hash 字段：最新浏览量绝对值（HSET 覆盖语义，可重放幂等，来源 content.view-count-flushed） */
    public static final String HEAT_PENDING_FIELD_VIEW = "view_cnt";

    /** pending Hash 字段：点赞增量（HINCRBY 累加不可重入，幂等靠 eventId 去重，来源 social.liked） */
    public static final String HEAT_PENDING_FIELD_LIKE = "like_delta";

    /** pending Hash 字段：评论增量（HINCRBY 累加不可重入，幂等靠 eventId 去重，来源 social.commented） */
    public static final String HEAT_PENDING_FIELD_COMMENT = "comment_delta";

    // ==================== 热点查询缓存（批次四 4.1 + 3.2 遗留失效接入） ====================

    /**
     * 热点查询缓存键前缀（完整键 = search:hotquery:{type}:{sort}:{pageNum}:{pageSize}:{查询指纹}）：
     * 遵循企业缓存键规范 search:模块:功能:唯一标识；查询指纹为关键词与筛选条件的摘要，
     * 避免把用户输入原样拼进键（防超长键与特殊字符）
     */
    public static final String HOT_QUERY_KEY_PREFIX = "search:hotquery:";

    /** 热点查询缓存键层级分隔符（模块/功能/标识之间统一使用冒号） */
    public static final String CACHE_KEY_SEPARATOR = ":";

    /** 查询指纹拼接分隔符（关键词与各筛选项拼接后再做摘要，防字段边界混淆） */
    public static final String CACHE_IDENTITY_SEPARATOR = "|";

    /** 热点查询缓存全量失效时单次 SCAN 建议返回条数（降低批量删除时的往返次数） */
    public static final int HOT_QUERY_EVICT_SCAN_COUNT = 500;

    /**
     * 热点查询缓存「待失效」脏标记键。
     * <p>刻意不用 search:hotquery: 前缀：evictAll 按 search:hotquery:* 扫描，
     * 若脏标记也落在这个前缀内会被一并删除，导致合并任务漏掉一次失效。</p>
     */
    public static final String HOT_QUERY_DIRTY_KEY = "search:hotquery-dirty";

    /**
     * 脏标记 TTL（秒）：防合并任务长时间宕机或 Redis 抖动时标记永久丢失；
     * 过期后最多再延迟一个间隔即可由后续事件重新打标。
     */
    public static final long HOT_QUERY_DIRTY_TTL_SECONDS = 300;

    // ==================== 文章下架墓碑 ====================

    /**
     * 文章「下架墓碑」键前缀（后缀为 articleId）。
     * <p>用途：下架事件删除索引后写一个短 TTL 标记，发布/元数据路径写入前查此标记并强制重新门控，
     * 防止跨队列竞态下旧发布事件把已下架文章重新写回索引（隐私红线兜底）。</p>
     * <p>只对文章生效：书籍/用户没有「下架」语义，删除即最终态。</p>
     */
    public static final String ARTICLE_TOMBSTONE_KEY_PREFIX = "search:index:tombstone:article:";
}
