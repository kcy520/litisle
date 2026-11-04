package com.litisle.litislesearch.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litislesearch.config.SearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HTTP Embedding 提供方抽象基类（批次七）：统一批量、超时、鉴权与失败降级逻辑。
 *
 * 【职责边界】
 * 1. 批量：按 `embedding.batch-size` 切分，单个子批失败只把该批结果置 null，不中断其余批次；
 * 2. 超时：连接超时与请求超时均取 `embedding.timeout`；
 * 3. 鉴权：`embedding.api-key` 非空才发送 Bearer 头（TEI 内网无鉴权场景不发空头）；
 * 4. 失败降级：任何网络/解析异常都返回 null，由 VectorService 跳过向量字段，绝不阻断索引写入。
 *
 * 【子类职责】只负责：提供方标识、端点路径、请求体字段、响应体解析。
 *
 * @author litisle
 */
@Slf4j
public abstract class AbstractHttpEmbeddingProvider implements EmbeddingProvider {

    /** HTTP 200 视为成功 */
    private static final int HTTP_STATUS_OK = 200;

    /** 请求头：内容类型 */
    private static final String HEADER_CONTENT_TYPE = "Content-Type";

    /** 请求头：鉴权 */
    private static final String HEADER_AUTHORIZATION = "Authorization";

    /** JSON 内容类型 */
    private static final String CONTENT_TYPE_JSON = "application/json";

    /** Bearer 鉴权前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 超时缺省值（配置缺失时兜底，避免 HttpClient 构造失败） */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** 失败响应体日志截断长度（防大响应刷屏） */
    private static final int ERROR_BODY_LOG_LIMIT = 200;

    /** search 配置（读取 embedding 提供方参数） */
    protected final SearchProperties properties;

    /** JSON 序列化/反序列化（请求体与响应解析） */
    protected final ObjectMapper objectMapper;

    /** 复用的 HTTP 客户端（连接超时取配置 timeout） */
    private final HttpClient httpClient;

    /**
     * 构造 HTTP 提供方基类。
     *
     * @param properties   search 配置
     * @param objectMapper JSON 处理器
     */
    protected AbstractHttpEmbeddingProvider(SearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 连接超时取配置；配置为空时用兜底值（请求级超时另在 postJson 设置）
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(configTimeout())
                .build();
    }

    /**
     * 模型标识（来自配置 embedding.model）。
     *
     * @return 模型名
     */
    @Override
    public String modelName() {
        return properties.getEmbedding().getModel();
    }

    /**
     * 本地配置是否就绪（只判 base-url 与 model，不发起网络请求）。
     *
     * @return true=可调用
     */
    @Override
    public boolean available() {
        return StringUtils.hasText(baseUrl()) && StringUtils.hasText(modelName());
    }

    /**
     * 单条文本向量化（内部走批量接口，保证与批量路径同一套降级语义）。
     *
     * @param text 待向量化文本
     * @return 向量；失败返回 null
     */
    @Override
    public List<Float> embed(String text) {
        // 空文本无需调用提供方
        if (!StringUtils.hasText(text)) {
            return null;
        }
        List<List<Float>> vectors = embedBatch(Collections.singletonList(text));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    /**
     * 批量向量化：按配置批量切分，单批失败只影响该批。
     *
     * @param texts 文本列表
     * @return 与输入等长的向量列表（失败项为 null）
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<Float>> result = new ArrayList<>(texts.size());
        // 配置未就绪（缺 base-url/model）：返回等长 null 列表，调用方跳过向量写入
        if (!available()) {
            for (int i = 0; i < texts.size(); i++) {
                result.add(null);
            }
            return result;
        }
        // 按批量大小切分，降低单次请求负载
        int batchSize = Math.max(1, properties.getEmbedding().getBatchSize());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> subTexts = texts.subList(start, end);
            List<List<Float>> vectors = requestBatch(subTexts);
            // 子批失败/返回条数不符：整批置 null，保证下标与输入一一对应
            if (vectors == null || vectors.size() != subTexts.size()) {
                for (int i = 0; i < subTexts.size(); i++) {
                    result.add(null);
                }
            } else {
                result.addAll(vectors);
            }
        }
        return result;
    }

    /**
     * 子类实现：向自身提供方发起一次批量向量化请求。
     *
     * @param texts 本批文本（长度不超过 batch-size）
     * @return 与输入等长的向量列表；失败返回 null
     */
    protected abstract List<List<Float>> requestBatch(List<String> texts);

    /**
     * 发送 JSON POST 请求。
     *
     * @param path        端点路径（以 / 开头，与 base-url 拼接）
     * @param requestBody 请求体对象（Jackson 序列化）
     * @return 成功响应体；网络异常/非 200 返回 null
     */
    protected String postJson(String path, Object requestBody) {
        String url = baseUrl() + path;
        try {
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            // 请求级超时（配置为空时不设置，由客户端默认策略兜底）
            Duration timeout = properties.getEmbedding().getTimeout();
            if (timeout != null) {
                builder.timeout(timeout);
            }
            // 密钥非空才加 Bearer（内网 TEI 无鉴权场景不发送空令牌）
            String apiKey = properties.getEmbedding().getApiKey();
            if (StringUtils.hasText(apiKey)) {
                builder.header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey.trim());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != HTTP_STATUS_OK) {
                log.warn("[向量化] 提供方返回非 200：provider={}, url={}, status={}, body={}",
                        providerName(), url, response.statusCode(), abbreviate(response.body()));
                return null;
            }
            return response.body();
        } catch (InterruptedException e) {
            // 恢复中断标记，避免吞掉线程中断信号
            Thread.currentThread().interrupt();
            log.warn("[向量化] 提供方调用被中断：provider={}, url={}", providerName(), url);
            return null;
        } catch (Exception e) {
            // 网络/序列化异常：降级为 null，由 VectorService 跳过向量写入
            log.warn("[向量化] 提供方调用失败：provider={}, url={}", providerName(), url, e);
            return null;
        }
    }

    /**
     * JSON 数组节点转向量。
     *
     * @param node 向量数组节点
     * @return 向量；节点缺失/非数组返回 null
     */
    protected List<Float> toVector(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<Float> vector = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            vector.add((float) value.asDouble());
        }
        return vector;
    }

    /**
     * 读取并规整 base-url（去掉末尾斜杠，避免与端点拼接成双斜杠）。
     *
     * @return 规整后的 base-url；未配置返回 null
     */
    protected String baseUrl() {
        String configured = properties.getEmbedding().getBaseUrl();
        if (!StringUtils.hasText(configured)) {
            return null;
        }
        String trimmed = configured.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 配置超时（为空时用兜底值）。
     *
     * @return 连接超时
     */
    private Duration configTimeout() {
        Duration timeout = properties.getEmbedding().getTimeout();
        return timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /**
     * 截断响应体用于日志（防大响应刷屏，也避免把长文本写入日志）。
     *
     * @param body 响应体
     * @return 截断后的文本
     */
    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= ERROR_BODY_LOG_LIMIT ? body : body.substring(0, ERROR_BODY_LOG_LIMIT) + "...";
    }
}
