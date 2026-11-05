package com.litisle.litislesearch.constant;

/**
 * search 模块业务常量（架构文档 §1.2 constant 包：索引/死信拓扑命名 + ES 字段名收口）。
 *
 * 说明：可调参数（权重/阈值/超时等）一律外置 yml 的 litisle.search.* 段（见 SearchProperties），
 * 本类只收口「结构命名」与「ES 字段名」两类不适合外置的常量，避免业务代码字符串硬编码
 */
public final class SearchBizConstant {

    /** 工具类禁止实例化 */
    private SearchBizConstant() {
    }

    // ==================== 死信拓扑（索引同步消费失败兜底） ====================

    /**
     * 索引同步死信交换机（Direct，durable）：独立于 content/social 业务交换机，
     * Direct 精确匹配无通配语义，杜绝业务侧通配绑定误吸死信（与 file 模块 moderation.dlx 同一设计）
     */
    public static final String EXCHANGE_SEARCH_DLX = "litisle.search.dlx";

    /**
     * 索引同步死信队列（durable）：六个同步队列
     * （content.published / content.unpublished / content.article-updated / content.view-count-flushed /
     * social.liked / social.commented）消费重试耗尽后经 DLX 按原路由键统一汇入，供人工排查/重投
     */
    public static final String QUEUE_SEARCH_INDEX_DLQ = "litisle.search.index.dlq.queue";

    /**
     * 索引同步延迟重投交换机（x-delayed-message，durable，批次二 2.4）：
     * 消费异常时按指数退避把消息延迟投回原队列（routingKey=队列名），
     * 依赖 broker 启用 rabbitmq_delayed_message_exchange 插件（docker 已启用）
     */
    public static final String EXCHANGE_SEARCH_DELAY = "litisle.search.delay.exchange";

    /** 延迟重投交换机类型（RabbitMQ 延迟插件固定值，非普通 direct/topic 类型） */
    public static final String EXCHANGE_TYPE_DELAYED = "x-delayed-message";

    /** 延迟交换机参数键：实际投递类型（延迟插件要求显式声明底层交换机类型） */
    public static final String DELAYED_EXCHANGE_ARG_TYPE = "x-delayed-type";

    /** 延迟交换机底层投递类型值（Direct，与队列绑定键精确匹配） */
    public static final String DELAYED_EXCHANGE_TYPE_DIRECT = "direct";

    // ==================== content 文章状态/可见性门控值（与 content 枚举 code 对齐） ====================

    /**
     * 文章已发布状态 code（content ArticleStatusEnum.PUBLISHED=2）：
     * 乱序门控条件之一——回查后 status 必须为 2（已审核通过或免审直落），否则丢弃不写，
     * 防 unpublished 先到、published 迟到把已删内容写回索引
     */
    public static final Integer ARTICLE_STATUS_PUBLISHED = 2;

    /**
     * 文章公开可见性 code（content VisibilityEnum.PUBLIC=1）：
     * 乱序门控条件之二——仅 PUBLIC 文章入索引（写入侧闸门，查询侧另有 filter 双保险）
     */
    public static final Integer ARTICLE_VISIBILITY_PUBLIC = 1;

    // ==================== article_chunks 切片收口 ====================

    /** 切片主键分隔符（chunk_id = articleId + 分隔符 + 序号，保证同文章内唯一且可溯源） */
    public static final String CHUNK_ID_SEPARATOR = "_";

    /**
     * 文章向量化输入最大字符数（默认 2000）：
     * Embedding 模型普遍有输入长度上限，超长正文截断后再向量化，防请求被模型拒绝；
     * 截断只影响向量语义覆盖范围，不影响全文检索字段 content（完整入索引）
     */
    public static final int EMBEDDING_INPUT_MAX_CHARS = 2000;

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

    // ==================== 索引文档可见性闸门值 ====================

    /**
     * 入索引可见性值（写入侧闸门 + 查询侧 filter 双保险，架构文档 §四）：
     * 仅 PUBLIC 文章/书籍/用户文档入索引与向量库，私有/草稿/拒绝永不入索引（与 review 可见性红线同源）
     */
    public static final String VISIBILITY_PUBLIC = "PUBLIC";

    // ==================== 查询词/Embedding 收口 ====================

    /**
     * 查询词最大长度（字符数，批次一 1.2 定版）：
     * search_history.keyword 列长度（varchar(64)）与本常量同源，
     * 批次四参数校验落地时按本常量拒绝超长查询词（SEARCH_PARAM_INVALID）
     */
    public static final int SEARCH_KEYWORD_MAX_LENGTH = 64;

    /** Embedding 向量维度（批次C C1 定版：vec_dense 与 chunk_vector 统一单模型，维度一致，一期 1024） */
    public static final int EMBEDDING_DIMS = 1024;

    /**
     * Embedding 提供方「未接入」标识（对应配置 litisle.search.embedding.provider=none）：
     * VectorService 据此跳过向量写入，索引其余字段不受影响
     */
    public static final String EMBEDDING_PROVIDER_NONE = "none";

    /** Embedding 配置前缀（提供方适配器条件装配用，批次七选型定案） */
    public static final String EMBEDDING_PROPERTY_PREFIX = "litisle.search.embedding";

    /** Embedding 配置项名：提供方类型（条件装配 + 日志定位用） */
    public static final String EMBEDDING_PROPERTY_PROVIDER_NAME = "provider";

    /** Embedding 提供方：OpenAI 兼容 /v1/embeddings（批次七主适配器，覆盖 SiliconFlow/百炼兼容入口/TEI） */
    public static final String EMBEDDING_PROVIDER_OPENAI_COMPATIBLE = "openai-compatible";

    /** Embedding 提供方：Ollama /api/embed（批次七备选适配器，不做主线） */
    public static final String EMBEDDING_PROVIDER_OLLAMA = "ollama";

    /** ik 中文分词器：细粒度切分（写入侧 analyzer，索引时尽量多切词提升召回） */
    public static final String ANALYZER_IK_MAX_WORD = "ik_max_word";

    /** ik 中文分词器：粗粒度切分（查询侧 search_analyzer，搜索词按词组匹配防过碎误召回） */
    public static final String ANALYZER_IK_SMART = "ik_smart";

    // ==================== 索引元信息（_meta，批次C C1：向量模型版本登记防漂移） ====================

    /** mappings._meta 键：Embedding 模型标识（模型变更 → 全量重建索引，架构文档 §八-8） */
    public static final String META_EMBEDDING_MODEL = "embedding_model";

    /** mappings._meta 键：Embedding 向量维度（与 EMBEDDING_DIMS 同源写入，防 mapping 与代码错位） */
    public static final String META_EMBEDDING_DIMS = "embedding_dims";

    /**
     * 一期 Embedding 模型占位标识（批次C C1：具体选型随 VectorService 落地定，维度先行收口 1024；
     * 模型确定后改写本值并触发全量重建索引）
     * <p>2026-11-05 起索引 _meta 直接写配置项 embedding.model，本常量已无代码引用；
     * 保留作历史占位/兼容旧索引识别用途，勿删。</p>
     */
    public static final String EMBEDDING_MODEL_PLACEHOLDER = "unset-1024d";

    // ==================== multi-field 联想子字段名 ====================

    /** completion 联想子字段名（title.completion / authorName.completion，仅联想用不参与打分） */
    public static final String FIELD_COMPLETION_SUB = "completion";

    // ==================== book_idx 字段名（ES mapping 字段名收口，架构文档 §四） ====================

    /** 书籍分类（keyword，筛选维度） */
    public static final String FIELD_CATEGORY = "category";

    /**
     * 书籍下架状态 code（content BookStatusEnum.TAKEN_DOWN=2）：
     * 下架=对外终止态，回查后命中即删除 book_idx 文档（与软删删除同一收敛路径）
     */
    public static final Integer BOOK_STATUS_TAKEN_DOWN = 2;

    // ==================== book_idx / user_idx 写入门控值（批次五 B4/B5） ====================

    /**
     * 用户正常状态 code（common UserStatus.NORMAL=1）：
     * 仅正常状态用户入 user_idx（禁用/注销用户删除索引文档）
     */
    public static final Integer USER_STATUS_NORMAL = 1;

    /**
     * 未删除标志 code（common DelFlag.NOT_DELETED=0）：
     * 已删除的书籍/用户一律不入索引（回查命中已删除即删索引文档）
     */
    public static final Integer DEL_FLAG_NOT_DELETED = 0;

    // ==================== user_idx 字段名（ES mapping 字段名收口，架构文档 §四） ====================

    /** 用户笔名（text + ik，模糊匹配检索） */
    public static final String FIELD_PEN_NAME = "penName";

    /** 用户简介（text + ik） */
    public static final String FIELD_BIO = "bio";

    // ==================== article_chunks 字段名（RAG 知识库索引，架构文档 §四） ====================

    /** 切片主键（keyword，格式 articleId_seq，按 articleId 幂等覆盖的删除依据之一） */
    public static final String FIELD_CHUNK_ID = "chunk_id";

    /** 所属文章 ID（long，按 articleId 幂等覆盖删旧写新） */
    public static final String FIELD_ARTICLE_ID = "article_id";

    /** 切片正文（text + ik，小月 RAG 召回文本） */
    public static final String FIELD_CHUNK_TEXT = "chunk_text";

    /** 切片向量（dense_vector dims=1024 cosine indexed，与 vec_dense 同一 Embedding 单模型，批次C C1） */
    public static final String FIELD_CHUNK_VECTOR = "chunk_vector";

    /** 所属分区（keyword，RAG 检索可选过滤维度） */
    public static final String FIELD_ZONE = "zone";

    /** 发布时间（date，RAG 检索可选时间过滤/引用卡片展示） */
    public static final String FIELD_PUBLISH_TIME = "publish_time";

    /** 摄入时间（date，切片入库时间，重建排查用） */
    public static final String FIELD_INGEST_TIME = "ingest_time";

    // ==================== article_idx 字段名（ES mapping 字段名收口，架构文档 §四） ====================

    /** 文档主键字段：articleId（幂等 upsert 依据） */
    public static final String FIELD_ID = "id";

    /** 标题（text + ik，multi_match 权重 ^4，multi-field 含 .completion 子字段作联想源） */
    public static final String FIELD_TITLE = "title";

    /** 标签（keyword[]，权重 ^3，兼作相似推荐的标签重合度计算） */
    public static final String FIELD_TAGS = "tags";

    /** 摘要（text，权重 ^2） */
    public static final String FIELD_SUMMARY = "summary";

    /** 正文（text + ik，全文检索，返回高亮片段） */
    public static final String FIELD_CONTENT = "content";

    /** 可见性过滤字段（keyword，仅 PUBLIC 入索引，查询侧强制 filter） */
    public static final String FIELD_VISIBILITY = "visibility";

    /** 作者 ID（keyword，作者维度聚合/筛选） */
    public static final String FIELD_AUTHOR_ID = "authorId";

    /** 作者名（text + ik，multi-field 含 .completion 子字段作联想源） */
    public static final String FIELD_AUTHOR_NAME = "authorName";

    /** 点赞数（long，heat 计算基数，随互动事件增量） */
    public static final String FIELD_LIKE_CNT = "like_cnt";

    /** 评论数（long，heat 计算基数，随互动事件增量） */
    public static final String FIELD_COMMENT_CNT = "comment_cnt";

    /** 浏览量（long，heat 计算基数，随浏览量 flush 事件覆盖更新） */
    public static final String FIELD_VIEW_CNT = "view_cnt";

    /** 互动热度（double，function_score 加权因子 + HEAT 排序，公式见架构文档 §四） */
    public static final String FIELD_HEAT = "heat";

    /** 文章向量（dense_vector dims=1024 cosine indexed，KNN 相似推荐） */
    public static final String FIELD_VEC_DENSE = "vec_dense";

    /** 更新时间（date，NEWEST 排序） */
    public static final String FIELD_UPDATE_TIME = "updateTime";

    // ==================== 全文检索默认值与结果来源（批次四 4.1 / 4.2） ====================

    /** 检索默认页码（请求未传 pageNum 时从第一页开始） */
    public static final int SEARCH_DEFAULT_PAGE_NUM = 1;

    /** 检索默认每页条数（请求未传 pageSize 时使用，且不超过 page.max-page-size） */
    public static final int SEARCH_DEFAULT_PAGE_SIZE = 10;

    /** 结果来源标记：常规 ES 检索命中（与 SearchResultVO.source 契约一致，可写入热点查询缓存） */
    public static final String SEARCH_SOURCE_ES = "ES";

    /**
     * 结果来源标记：空结果兜底（查询词实时 Embedding + KNN，或二级 HEAT 热门列表）：
     * 兜底结果不是真实命中，禁止写入热点查询缓存（防把兜底当真命中）
     */
    public static final String SEARCH_SOURCE_FALLBACK = "fallback";

    /**
     * 结果来源标记：ES 整体不可用时的热门列表降级（批次A A3 定案 / 批次八 8.1）：
     * 数据来自 content 热门榜（与 /article/hot 同源），与查询词无关；
     * 禁止写入热点查询缓存——按查询键缓存会把同一份热门复制到每个关键词下白占内存，
     * 且 ES 恢复后缓存 TTL 内仍会返回降级结果，掩盖服务已恢复的事实
     */
    public static final String SEARCH_SOURCE_DEGRADED = "DEGRADED";

    /** 高亮片段目标字符数（单片段，前端摘要卡片展示足够） */
    public static final int HIGHLIGHT_FRAGMENT_SIZE = 80;

    /** 高亮片段数量（每字段只取 1 段，保持 VO 单 highlight 字段语义） */
    public static final int HIGHLIGHT_FRAGMENT_COUNT = 1;

    /** 发布时间筛选支持的第一格式：纯日期（yyyy-MM-dd） */
    public static final String PUBLISH_TIME_DATE_PATTERN = "yyyy-MM-dd";

    /** 纯日期字符串长度（用于区分「纯日期」与「日期时间」两种输入格式） */
    public static final int PUBLISH_TIME_DATE_LENGTH = 10;

    /** 发布时间筛选支持的第二格式（第一批为 ISO-8601，另兼容「yyyy-MM-dd HH:mm:ss」） */
    public static final String PUBLISH_TIME_DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

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

    /**
     * 文章「下架墓碑」键前缀（后缀为 articleId）。
     * <p>用途：下架事件删除索引后写一个短 TTL 标记，发布/元数据路径写入前查此标记并强制重新门控，
     * 防止跨队列竞态下旧发布事件把已下架文章重新写回索引（隐私红线兜底）。</p>
     * <p>只对文章生效：书籍/用户没有「下架」语义，删除即最终态。</p>
     */
    public static final String ARTICLE_TOMBSTONE_KEY_PREFIX = "search:index:tombstone:article:";

    /**
     * ES date 字段统一格式（批次九 9.9-A）：
     * 严格 ISO-8601（date_optional_time 允许省略时间部分）+ epoch 毫秒兜底。
     * <p>与 ES 客户端专用 ObjectMapper 的 ISO 序列化口径一致；显式声明避免依赖集群默认格式。</p>
     */
    public static final String ES_DATE_FORMAT = "strict_date_optional_time||epoch_millis";

    // ==================== 搜索联想（批次六 6.1） ====================

    /** 联想类型：书名（article_idx title.completion） */
    public static final String SUGGEST_TYPE_KEYWORD = "KEYWORD";

    /** 联想类型：作者（article_idx authorName.completion） */
    public static final String SUGGEST_TYPE_AUTHOR = "AUTHOR";

    /** 联想类型：标签（content 标签池前缀查询，不建索引） */
    public static final String SUGGEST_TYPE_TAG = "TAG";

    /** completion 子字段路径分隔符（父字段名 + "." + 子字段名） */
    public static final String SUGGEST_COMPLETION_FIELD_SEPARATOR = ".";

    /** completion 子字段名（批次一 mapping 已定义 title/authorName 的 completion 子字段） */
    public static final String SUGGEST_COMPLETION_SUFFIX = "completion";

    /** 书名联想字段路径：title.completion */
    public static final String SUGGEST_FIELD_TITLE_COMPLETION =
            FIELD_TITLE + SUGGEST_COMPLETION_FIELD_SEPARATOR + SUGGEST_COMPLETION_SUFFIX;

    /** 作者联想字段路径：authorName.completion */
    public static final String SUGGEST_FIELD_AUTHOR_NAME_COMPLETION =
            FIELD_AUTHOR_NAME + SUGGEST_COMPLETION_FIELD_SEPARATOR + SUGGEST_COMPLETION_SUFFIX;

    /** 书名 completion suggester 名称（响应按名称分组解析） */
    public static final String SUGGESTER_NAME_TITLE = "title_suggest";

    /** 作者 completion suggester 名称（响应按名称分组解析） */
    public static final String SUGGESTER_NAME_AUTHOR = "author_suggest";

    // ==================== 联想风控（企业优化清单 1.1 / 1.3） ====================

    /** 联想最小输入长度（字符）：单字/空串不触发联想，砍掉无效的 completion/标签池查询（1.1） */
    public static final int SUGGEST_MIN_KEYWORD_LENGTH = 2;

    /** 联想匿名限流窗口（秒）：与内容服务限流矩阵统一 60s（1.3） */
    public static final int SUGGEST_RATE_WINDOW_SECONDS = 60;

    /** 联想匿名限流阈值（次/窗口/IP）：输入联想为匿名只读接口，按 IP 防爬（1.3） */
    public static final int SUGGEST_IP_MAX_COUNT = 20;

    // ==================== 搜索历史（批次六 6.2） ====================

    /** search_history 表名（search 唯一自有表） */
    public static final String TABLE_SEARCH_HISTORY = "search_history";
}
