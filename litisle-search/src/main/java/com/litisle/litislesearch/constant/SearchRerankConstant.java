package com.litisle.litislesearch.constant;

/**
 * search 模块本地 reranker 精排常量（search 板块三 3.2 升级②）：
 * 覆盖提供方标识与配置项名、候选文档截断上限。
 *
 * 说明：rerank 适配器条件装配与协议固定值不适合外置 yml，统一收口避免字符串硬编码；
 * 重排为 0 次 LLM 的本地推理精排（bge-reranker / cross-encoder 对 (queryText, 候选摘要) 打分），
 * 失败/未启用一律退回三路融合基线排序（结果不劣化）。
 *
 * @author litisle
 */
public final class SearchRerankConstant {

    /** 工具类禁止实例化 */
    private SearchRerankConstant() {
    }

    /**
     * rerank 提供方「未接入」标识（对应配置 litisle.search.rerank.provider=none）：
     * RerankService 据此视为未接入，直接退回基线排序
     */
    public static final String RERANK_PROVIDER_NONE = "none";

    /** rerank 配置前缀（提供方适配器条件装配用） */
    public static final String RERANK_PROPERTY_PREFIX = "litisle.search.rerank";

    /** rerank 配置项名：提供方类型（条件装配 + 日志定位用） */
    public static final String RERANK_PROPERTY_PROVIDER_NAME = "provider";

    /**
     * rerank 提供方：OpenAI 兼容 /v1/rerank 协议（主适配器，
     * 覆盖 SiliconFlow rerank、Jina/Cohere 兼容入口、自建 rerank 网关）
     */
    public static final String RERANK_PROVIDER_OPENAI_COMPATIBLE = "openai-compatible";

    /**
     * 重排候选文档最大字符数（默认 512）：
     * bge-reranker 类 cross-encoder 模型输入普遍 512 token 上限，
     * 候选「标题 + 摘要」拼接后超限截断，防请求被模型拒绝（截断只影响打分精度，不丢弃候选）
     */
    public static final int RERANK_DOC_MAX_CHARS = 512;
}
