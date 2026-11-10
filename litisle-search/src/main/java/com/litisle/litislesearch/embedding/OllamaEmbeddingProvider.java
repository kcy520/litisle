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
        prefix = SearchEmbeddingConstant.EMBEDDING_PROPERTY_PREFIX,
        name = SearchEmbeddingConstant.EMBEDDING_PROPERTY_PROVIDER_NAME,
        havingValue = SearchEmbeddingConstant.EMBEDDING_PROVIDER_OLLAMA)
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
        return SearchEmbeddingConstant.EMBEDDING_PROVIDER_OLLAMA;
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
         * 1. 组装 Ollama 批量协议请求体：模型名 + 本批文本数组（/api/embed，Ollama 0.2.11+ 批量接口）；
         * 2. 调用基类 postJson 发送请求，响应体为 null（网络失败/非 200）时直接降级返回 null；
         * 3. 解析响应 JSON：取 embeddings 嵌套数组（数组套数组），结构异常（旧版单条格式）记日志后返回 null；
         * 4. 遍历 embeddings，逐条把向量数组转为向量列表并返回；
         * 5. 解析抛异常同样记日志返回 null——失败语义与 OpenAI 兼容适配器一致，不阻断索引写入。
         */

        // 协议请求体：模型 + 输入数组
        Map<String, Object> requestBody = Map.of(
                REQUEST_FIELD_MODEL, modelName(),
                REQUEST_FIELD_INPUT, texts);
        // 发送请求（失败/非 200 时基类已记日志并返回 null）
        String responseBody = postJson(ENDPOINT_EMBED, requestBody);
        if (responseBody == null) {
            return null;
        }
        try {
            // 响应原文解析为 JSON 树
            JsonNode root = objectMapper.readTree(responseBody);
            // 向量二维数组节点（外层是每条文本，内层是向量分量；path 缺失时返回缺失节点交给 isArray 判断）
            JsonNode embeddings = root.path(RESPONSE_FIELD_EMBEDDINGS);
            // 响应结构异常（旧版 Ollama 可能返回单条 embedding）：返回 null 由基类降级
            if (!embeddings.isArray()) {
                log.warn("[向量化] Ollama 响应缺少 embeddings 数组：provider={}", providerName());
                return null;
            }
            // 与本批输入等长的向量结果列表
            List<List<Float>> vectors = new ArrayList<>(embeddings.size());
            // 逐条把向量数组转为向量（item 本身就是数组，无需再取子字段；缺失/非数组时 toVector 返回 null 占位）
            for (JsonNode item : embeddings) {
                vectors.add(toVector(item));
            }
            return vectors;
        } catch (Exception e) {
            // JSON 解析异常：降级为 null，由基类把本批结果置空
            log.warn("[向量化] Ollama 响应解析失败：provider={}", providerName(), e);
            return null;
        }
    }
}
