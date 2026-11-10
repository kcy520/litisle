package com.litisle.litislesearch.constant;

/**
 * search 模块 ES mapping 字段名常量（架构文档 §四）：
 * 覆盖 article_idx / book_idx / user_idx / article_chunks 四个索引的字段名，
 * 以及 ik 分词器名、date 格式、_meta 键、multi-field 联想子字段名。
 *
 * 说明：字段名是 ES mapping 与业务代码的对齐依据，不适合外置 yml，统一收口避免字符串硬编码
 */
public final class SearchFieldConstant {

    /** 工具类禁止实例化 */
    private SearchFieldConstant() {
    }

    // ==================== ik 中文分词器 ====================

    /** ik 中文分词器：细粒度切分（写入侧 analyzer，索引时尽量多切词提升召回） */
    public static final String ANALYZER_IK_MAX_WORD = "ik_max_word";

    /** ik 中文分词器：粗粒度切分（查询侧 search_analyzer，搜索词按词组匹配防过碎误召回） */
    public static final String ANALYZER_IK_SMART = "ik_smart";

    // ==================== 索引元信息（_meta，批次C C1：向量模型版本登记防漂移） ====================

    /** mappings._meta 键：Embedding 模型标识（模型变更 → 全量重建索引，架构文档 §八-8） */
    public static final String META_EMBEDDING_MODEL = "embedding_model";

    /** mappings._meta 键：Embedding 向量维度（与 SearchEmbeddingConstant.EMBEDDING_DIMS 同源写入，防 mapping 与代码错位） */
    public static final String META_EMBEDDING_DIMS = "embedding_dims";

    /**
     * ES date 字段统一格式（批次九 9.9-A）：
     * 严格 ISO-8601（date_optional_time 允许省略时间部分）+ epoch 毫秒兜底。
     * <p>与 ES 客户端专用 ObjectMapper 的 ISO 序列化口径一致；显式声明避免依赖集群默认格式。</p>
     */
    public static final String ES_DATE_FORMAT = "strict_date_optional_time||epoch_millis";

    // ==================== multi-field 联想子字段名 ====================

    /** completion 联想子字段名（title.completion / authorName.completion，仅联想用不参与打分） */
    public static final String FIELD_COMPLETION_SUB = "completion";

    // ==================== article_idx 字段名 ====================

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

    // ==================== article_chunks 切片字段名（RAG 知识库索引） ====================

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

    /** 切片主键分隔符（chunk_id = articleId + 分隔符 + 序号，保证同文章内唯一且可溯源） */
    public static final String CHUNK_ID_SEPARATOR = "_";

    // ==================== book_idx 字段名 ====================

    /** 书籍分类（keyword，筛选维度） */
    public static final String FIELD_CATEGORY = "category";

    // ==================== user_idx 字段名 ====================

    /** 用户笔名（text + ik，模糊匹配检索） */
    public static final String FIELD_PEN_NAME = "penName";

    /** 用户简介（text + ik） */
    public static final String FIELD_BIO = "bio";
}
