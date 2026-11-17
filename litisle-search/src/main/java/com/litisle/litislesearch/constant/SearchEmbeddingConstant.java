package com.litisle.litislesearch.constant;

import com.litisle.litisleapi.util.ArticleVectorTextUtils;

/**
 * search 模块 Embedding 向量化常量（批次C C1 / 批次七选型定案收口）：
 * 覆盖向量维度、输入长度上限、提供方标识与配置项名、池化策略枚举值、_meta 历史占位。
 *
 * 说明：提供方适配器条件装配与向量写入依赖这些「协议固定值」，不适合外置 yml，统一收口避免字符串硬编码
 */
public final class SearchEmbeddingConstant {

    /** 工具类禁止实例化 */
    private SearchEmbeddingConstant() {
    }

    /**
     * 文章向量化输入最大字符数（默认 2000）：
     * Embedding 模型普遍有输入长度上限，超长正文截断后再向量化，防请求被模型拒绝；
     * 截断只影响向量语义覆盖范围，不影响全文检索字段 content（完整入索引）。
     * <p>search 板块一 1.1 起规则本体下沉 litisle-api {@link ArticleVectorTextUtils#EMBEDDING_INPUT_MAX_CHARS}
     * （search/ai 共享单点），本常量保留作既有引用别名。</p>
     */
    public static final int EMBEDDING_INPUT_MAX_CHARS = ArticleVectorTextUtils.EMBEDDING_INPUT_MAX_CHARS;

    /**
     * 切片向量池化策略：mean（逐维平均，默认口径）。
     * 对应配置 litisle.search.embedding.pool.strategy
     */
    public static final String POOL_STRATEGY_MEAN = "mean";

    /**
     * 切片向量池化策略：max-pool（逐维取各切片最大值，对多主题长文更尖锐但可能放大噪声，备选不默认）。
     * 对应配置 litisle.search.embedding.pool.strategy
     */
    public static final String POOL_STRATEGY_MAX_POOL = "max-pool";

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

    /**
     * 一期 Embedding 模型占位标识（批次C C1：具体选型随 VectorService 落地定，维度先行收口 1024；
     * 模型确定后改写本值并触发全量重建索引）
     * <p>2026-11-05 起索引 _meta 直接写配置项 embedding.model，本常量已无代码引用；
     * 保留作历史占位/兼容旧索引识别用途，勿删。</p>
     */
    public static final String EMBEDDING_MODEL_PLACEHOLDER = "unset-1024d";
}
