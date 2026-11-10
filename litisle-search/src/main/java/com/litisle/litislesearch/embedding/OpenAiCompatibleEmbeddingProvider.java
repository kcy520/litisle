package com.litisle.litislesearch.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchEmbeddingConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Embedding 提供方（批次七主适配器）。
 *
 * 这个类叫「OpenAI 兼容」，并不是因为依赖 OpenAI 公司的服务，而是因为它实现的是 OpenAI 的 HTTP 接口协议规范 ，这个规范已经成为行业事实标准。
 *
 * 【适配范围】所有兼容 OpenAI `/v1/embeddings` 协议的服务：
 * SiliconFlow（bge-m3）、阿里百炼 OpenAI 兼容入口、自建 OpenAI 网关、TEI（开启 OpenAI 兼容模式）。
 * 【协议】请求 `{ "model": "<embedding.model>", "input": ["文本..."] }`；
 * 响应 `{ "data": [ { "index": 0, "embedding": [...] } ] }`。
 * 【配置】provider=openai-compatible、base-url、model、api-key（可空）、timeout、batch-size 全部外置。
 * 【失败语义】网络/解析/非 200 一律返回 null，由 VectorService 跳过向量字段，不阻断索引写入。
 *
 * @author litisle
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = SearchEmbeddingConstant.EMBEDDING_PROPERTY_PREFIX,
        name = SearchEmbeddingConstant.EMBEDDING_PROPERTY_PROVIDER_NAME,
        havingValue = SearchEmbeddingConstant.EMBEDDING_PROVIDER_OPENAI_COMPATIBLE)
public class OpenAiCompatibleEmbeddingProvider extends AbstractHttpEmbeddingProvider {

    /** OpenAI 兼容批量向量端点 */
    private static final String ENDPOINT_EMBEDDINGS = "/v1/embeddings";

    /** 请求字段：模型名 */
    private static final String REQUEST_FIELD_MODEL = "model";

    /** 请求字段：输入文本数组 */
    private static final String REQUEST_FIELD_INPUT = "input";

    /** 响应字段：结果数组 */
    private static final String RESPONSE_FIELD_DATA = "data";

    /** 响应字段：单条向量 */
    private static final String RESPONSE_FIELD_EMBEDDING = "embedding";

    /**
     * 构造 OpenAI 兼容适配器。
     *
     * @param properties   search 配置
     * @param objectMapper JSON 处理器
     */
    public OpenAiCompatibleEmbeddingProvider(SearchProperties properties, ObjectMapper objectMapper) {
        super(properties, objectMapper);
    }

    /**
     * 提供方标识。
     *
     * @return openai-compatible
     */
    @Override
    public String providerName() {
        return SearchEmbeddingConstant.EMBEDDING_PROVIDER_OPENAI_COMPATIBLE;
    }

    /**
     * 发起一次批量向量化请求。
     *
     * @param texts 本批文本
     * @return 与输入等长的向量列表；失败返回 null
     */
    @Override
    protected List<List<Float>> requestBatch(List<String> texts) {
        /*
         * 业务逻辑步骤：
         * 1. 组装 OpenAI 兼容协议请求体：模型名 + 本批文本数组（单条也走批量协议，统一处理）；
         * 2. 调用基类 postJson 发送 POST /v1/embeddings 请求，响应体为 null（网络失败/非 200）时直接降级返回 null；
         * 3. 解析响应 JSON：取 data 数组，结构异常（缺 data 或非数组）记日志后返回 null，由基类把本批置空；
         * 4. 遍历 data 数组，逐条取出 embedding 字段转为向量列表并返回；
         * 5. 解析抛异常同样记日志返回 null——本类任何失败都只影响当前子批，不阻断索引写入。
         */
        
        // 协议请求体：模型 + 输入数组（始终用数组，单条也走批量协议）
        Map<String, Object> requestBody = Map.of(
                REQUEST_FIELD_MODEL, modelName(),
                REQUEST_FIELD_INPUT, texts);
        // 发送请求（失败/非 200 时基类已记日志并返回 null）
        String responseBody = postJson(ENDPOINT_EMBEDDINGS, requestBody);
        if (responseBody == null) {
            return null;
        }
        try {
            // 响应原文解析为 JSON 树
            JsonNode root = objectMapper.readTree(responseBody);
            // 结果数组节点（path 缺失时返回缺失节点而非抛异常，交给下方 isArray 判断）
            JsonNode data = root.path(RESPONSE_FIELD_DATA);
            // 响应结构异常：返回 null，由基类把本批结果置空
            if (!data.isArray()) {
                log.warn("[向量化] OpenAI 兼容响应缺少 data 数组：provider={}", providerName());
                return null;
            }
            // 与本批输入等长的向量结果列表
            List<List<Float>> vectors = new ArrayList<>(data.size());
            // 逐条取 item.embedding 转为向量（缺失/非数组时 toVector 返回 null 占位）
            for (JsonNode item : data) {
                vectors.add(toVector(item.path(RESPONSE_FIELD_EMBEDDING)));
            }
            return vectors;
        } catch (Exception e) {
            // JSON 解析异常：降级为 null，由基类把本批结果置空
            log.warn("[向量化] OpenAI 兼容响应解析失败：provider={}", providerName(), e);
            return null;
        }
    }
}
