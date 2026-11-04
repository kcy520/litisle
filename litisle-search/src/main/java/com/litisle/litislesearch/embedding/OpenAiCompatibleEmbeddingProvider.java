package com.litisle.litislesearch.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Embedding 提供方（批次七主适配器）。
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
        prefix = SearchBizConstant.EMBEDDING_PROPERTY_PREFIX,
        name = SearchBizConstant.EMBEDDING_PROPERTY_PROVIDER_NAME,
        havingValue = SearchBizConstant.EMBEDDING_PROVIDER_OPENAI_COMPATIBLE)
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
        return SearchBizConstant.EMBEDDING_PROVIDER_OPENAI_COMPATIBLE;
    }

    /**
     * 发起一次批量向量化请求。
     *
     * @param texts 本批文本
     * @return 与输入等长的向量列表；失败返回 null
     */
    @Override
    protected List<List<Float>> requestBatch(List<String> texts) {
        // 协议请求体：模型 + 输入数组（始终用数组，单条也走批量协议）
        Map<String, Object> requestBody = Map.of(
                REQUEST_FIELD_MODEL, modelName(),
                REQUEST_FIELD_INPUT, texts);
        String responseBody = postJson(ENDPOINT_EMBEDDINGS, requestBody);
        if (responseBody == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path(RESPONSE_FIELD_DATA);
            // 响应结构异常：返回 null，由基类把本批结果置空
            if (!data.isArray()) {
                log.warn("[向量化] OpenAI 兼容响应缺少 data 数组：provider={}", providerName());
                return null;
            }
            List<List<Float>> vectors = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                vectors.add(toVector(item.path(RESPONSE_FIELD_EMBEDDING)));
            }
            return vectors;
        } catch (Exception e) {
            log.warn("[向量化] OpenAI 兼容响应解析失败：provider={}", providerName(), e);
            return null;
        }
    }
}
