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
 * Ollama Embedding 提供方（批次七备选适配器，不做主线）。
 *
 * 【使用场景】本地/内网已部署 Ollama 且拉取 bge-m3 时，把 `embedding.provider` 切为 ollama 即可。
 * 【协议】请求 `{ "model": "<embedding.model>", "input": ["文本..."] }`；
 * 响应 `{ "embeddings": [ [ ... ], ... ] }`（Ollama 0.2.11+ 的批量接口）。
 * 【配置】provider=ollama、base-url（如 http://localhost:11434）、model、timeout、batch-size；
 * api-key 对 Ollama 一般不需要，配置为空即不发送鉴权头。
 * 【失败语义】与 OpenAI 兼容适配器一致：失败返回 null，不阻断索引写入。
 *
 * @author litisle
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = SearchBizConstant.EMBEDDING_PROPERTY_PREFIX,
        name = SearchBizConstant.EMBEDDING_PROPERTY_PROVIDER_NAME,
        havingValue = SearchBizConstant.EMBEDDING_PROVIDER_OLLAMA)
public class OllamaEmbeddingProvider extends AbstractHttpEmbeddingProvider {

    /** Ollama 批量向量端点 */
    private static final String ENDPOINT_EMBED = "/api/embed";

    /** 请求字段：模型名 */
    private static final String REQUEST_FIELD_MODEL = "model";

    /** 请求字段：输入文本数组 */
    private static final String REQUEST_FIELD_INPUT = "input";

    /** 响应字段：向量数组 */
    private static final String RESPONSE_FIELD_EMBEDDINGS = "embeddings";

    /**
     * 构造 Ollama 适配器。
     *
     * @param properties   search 配置
     * @param objectMapper JSON 处理器
     */
    public OllamaEmbeddingProvider(SearchProperties properties, ObjectMapper objectMapper) {
        super(properties, objectMapper);
    }

    /**
     * 提供方标识。
     *
     * @return ollama
     */
    @Override
    public String providerName() {
        return SearchBizConstant.EMBEDDING_PROVIDER_OLLAMA;
    }

    /**
     * 发起一次批量向量化请求。
     *
     * @param texts 本批文本
     * @return 与输入等长的向量列表；失败返回 null
     */
    @Override
    protected List<List<Float>> requestBatch(List<String> texts) {
        // 协议请求体：模型 + 输入数组
        Map<String, Object> requestBody = Map.of(
                REQUEST_FIELD_MODEL, modelName(),
                REQUEST_FIELD_INPUT, texts);
        String responseBody = postJson(ENDPOINT_EMBED, requestBody);
        if (responseBody == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode embeddings = root.path(RESPONSE_FIELD_EMBEDDINGS);
            // 响应结构异常（旧版 Ollama 可能返回单条 embedding）：返回 null 由基类降级
            if (!embeddings.isArray()) {
                log.warn("[向量化] Ollama 响应缺少 embeddings 数组：provider={}", providerName());
                return null;
            }
            List<List<Float>> vectors = new ArrayList<>(embeddings.size());
            for (JsonNode item : embeddings) {
                vectors.add(toVector(item));
            }
            return vectors;
        } catch (Exception e) {
            log.warn("[向量化] Ollama 响应解析失败：provider={}", providerName(), e);
            return null;
        }
    }
}
