package com.litisle.litisleai.provider.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiRagProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Infinity 本地重排通道（板块四 4.2 增强：Infinity 推理服务 + bge-reranker-v2-m3，零外发零成本）
 *
 * <p>【职责边界】provider 层唯一接触重排推理服务 HTTP 的实现：连接参数全部来自
 * {@link AiRagProperties} 外置配置；rerank.enabled=false 时本 Bean 不装配（通道缺席，
 * 调用方经 ObjectProvider 判空走向量原始排序，与向量通道 enabled 口径一致）。</p>
 *
 * <p>【协议】POST {baseUrl}{rerankPath}，请求体 {model, query, documents, top_n}，
 * 响应体 results:[{index, score}]（Infinity 原生 /rerank 协议，OpenAI 兼容网关改 rerank-path 即可）。</p>
 *
 * <p>【日志纪律】只记候选数与耗时，不打印文档正文。</p>
 *
 * <p>【失败语义】HTTP 非 2xx、响应结构异常、网络异常统一包装为 80005 上抛，
 * 由 RagSearchService 降级为向量原始排序（Q54 口径：重排是增强不是强依赖）。</p>
 *
 * <p>【通俗说明】重排通道的"真身"：连的是本地 Infinity 推理服务上跑的
 * bge-reranker-v2-m3 模型（数据不出服务器、不花钱）。它把 RerankProvider 接口的
 * "问题 + 候选进、相关性分数出"落到实处：构造时按 yml 配置拼好连接地址、模型名、
 * 超时时间（代码里不写死）；调用时先拒绝空输入，把候选打包成一次 HTTP 请求
 * （批量一次过，不逐条往返），拿到分数后核对响应里 index/score 齐全才返回。</p>
 *
 * @author litisle
 */
@Slf4j
public class InfinityRerankProvider implements RerankProvider {

    // ==================== 协议/日志固定参数（代码内唯一硬编码收口，其余参数全部外置 yml） ====================

    /** HTTP 建连超时（连接池建一次复用，固定 5 秒；请求级超时走配置 rerank.timeout） */
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** HTTP 成功状态码下界（含 200） */
    private static final int HTTP_SUCCESS_MIN = 200;

    /** HTTP 成功状态码上界（不含 300，即仅 2xx 视为成功） */
    private static final int HTTP_SUCCESS_MAX_EXCLUSIVE = 300;

    /** 异常响应体日志截断长度（日志纪律：异常现场只留前 200 字符，防大响应刷日志） */
    private static final int LOG_BODY_ABBREVIATE_LIMIT = 200;

    /** 响应 index 缺失兜底值（负数会被调用方的下标校验过滤掉） */
    private static final int INVALID_INDEX_FALLBACK = -1;

    /** 响应 score 缺失兜底值（0 分会在调用方的重排门槛过滤中被丢弃） */
    private static final double ZERO_SCORE_FALLBACK = 0.0D;

    // ==================== 协议字段名常量（请求体/响应体 JSON key，与 Infinity /rerank 契约一一对应） ====================

    /** 请求体字段：模型标识 */
    private static final String REQ_FIELD_MODEL = "model";

    /** 请求体字段：查询文本 */
    private static final String REQ_FIELD_QUERY = "query";

    /** 请求体字段：候选文档数组 */
    private static final String REQ_FIELD_DOCUMENTS = "documents";

    /** 请求体字段：返回条数上限（取候选全量，截断由调用方执行） */
    private static final String REQ_FIELD_TOP_N = "top_n";

    /** 响应体字段：精排结果数组 */
    private static final String RESP_FIELD_RESULTS = "results";

    /** 响应体字段：候选下标（对应请求 documents 的下标） */
    private static final String RESP_FIELD_INDEX = "index";

    /** 响应体字段：相关性分数 */
    private static final String RESP_FIELD_SCORE = "score";

    // ==================== HTTP/日志固定参数 ====================

    /** HTTP 请求头：内容类型 */
    private static final String HTTP_HEADER_CONTENT_TYPE = "Content-Type";

    /** 媒体类型：JSON（请求体编码） */
    private static final String MEDIA_TYPE_JSON = "application/json";

    /** 日志截断后缀（配合 LOG_BODY_ABBREVIATE_LIMIT 使用，提示响应体未完整记录） */
    private static final String LOG_BODY_TRUNCATE_SUFFIX = "...(截断)";

    /** 重排配置（连接参数来源） */
    private final AiRagProperties.Rerank rerankProperties;

    /** JDK 内置 HTTP 客户端（零额外依赖；连接超时固定 5 秒，请求超时取配置） */
    private final HttpClient httpClient;

    /** JSON 映射器（复用 Spring 全局 ObjectMapper 序列化口径） */
    private final ObjectMapper objectMapper;

    /**
     * 构造装配：连接参数全部取自外置配置，代码内不硬编码端点
     *
     * @param ragProperties RAG 配置（rerank 配置组来源）
     * @param objectMapper  Spring 全局 ObjectMapper
     *
     * <p>【通俗说明】构造时就把 HTTP 客户端按配置装配好（连接池复用建一次），
     * 后续每次调用直接复用，不用重复建连接。</p>
     */
    public InfinityRerankProvider(AiRagProperties ragProperties, ObjectMapper objectMapper) {
        this.rerankProperties = ragProperties.getRerank();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_CONNECT_TIMEOUT)
                .build();
    }

    /**
     * 对候选文档按与查询的相关性精排（批量一次请求；失败包装 80005 上抛转调用方降级）
     *
     * @param query     用户查询
     * @param documents 候选文档列表（向量粗筛序）
     * @return 精排结果（index 对应 documents 下标，含全部候选）
     * @throws BizException 空输入、HTTP 异常或响应结构异常（80005）
     *
     * <p>
     * 【通俗说明】四步：
     * ① 空输入直接拒（fail-fast 防无意义调用）；
     * ② 组装请求体
     * 批量发给本地 Infinity（一次往返算完所有候选，不逐条排队）；
     * ③ 非 2xx 响应记
     * 状态码与截断响应体后报错（转调用方降级）；
     * ④ 解析 results 数组返回 index+score列表，响应缺 results 视为通道异常而不是空结果——防"假成功"把排序悄悄洗掉。
     * </p>
     */
    @Override
    public List<RerankResult> rerank(String query, List<String> documents) {
        // 空查询/空候选无重排意义：fail-fast 防无意义调用
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "重排输入为空");
        }
        long start = System.currentTimeMillis();
        try {
            // 组装 Infinity /rerank 请求体（字段名取协议常量；top_n 取候选全量，截断排序由调用方执行）
            ObjectNode body = objectMapper.createObjectNode();
            body.put(REQ_FIELD_MODEL, rerankProperties.getModel());
            body.put(REQ_FIELD_QUERY, query);
            ArrayNode docs = body.putArray(REQ_FIELD_DOCUMENTS);
            documents.forEach(docs::add);
            body.put(REQ_FIELD_TOP_N, documents.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(rerankProperties.getBaseUrl() + rerankProperties.getRerankPath()))
                    .timeout(rerankProperties.getTimeout())
                    .header(HTTP_HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // 非 2xx 视为失败：记录状态码与截断响应体，包装 80005 转调用方降级
            if (response.statusCode() < HTTP_SUCCESS_MIN || response.statusCode() >= HTTP_SUCCESS_MAX_EXCLUSIVE) {
                throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                        "重排通道响应异常 status=" + response.statusCode()
                                + " body=" + abbreviate(response.body()));
            }
            List<RerankResult> results = parseResults(response.body());
            // 日志纪律：只记候选数与耗时
            log.info("Infinity 重排完成 docCount={} costMs={}", documents.size(), System.currentTimeMillis() - start);
            return results;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // HTTP/网络异常 fail-fast：检索侧统一降级为向量原始排序
            log.warn("Infinity 重排失败 docCount={} costMs={}",
                    documents.size(), System.currentTimeMillis() - start, e);
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "重排通道调用失败", e);
        }
    }

    /**
     * 解析响应 results 数组为精排结果（缺 results 视为通道异常，防假成功洗掉排序）
     *
     * @param json 响应体
     * @return 精排结果列表
     * @throws IOException JSON 解析失败
     * @throws BizException 响应缺 results 数组（80005）
     */
    private List<RerankResult> parseResults(String json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode results = root.path(RESP_FIELD_RESULTS);
        // 响应缺 results 或非数组：通道契约异常，按失败处理（返回空列表会被误当"全部低分"）
        if (!results.isArray() || results.isEmpty()) {
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "重排响应缺少 results 数组");
        }
        List<RerankResult> list = new ArrayList<>(results.size());
        for (JsonNode item : results) {
            // index/score 缺失时给兜底值（index=-1 会被调用方的下标校验过滤掉）
            list.add(new RerankResult(item.path(RESP_FIELD_INDEX).asInt(INVALID_INDEX_FALLBACK),
                    item.path(RESP_FIELD_SCORE).asDouble(ZERO_SCORE_FALLBACK)));
        }
        return list;
    }

    /**
     * 响应体截断（日志纪律：异常现场只留前 200 字符，防正文/大响应刷日志）
     *
     * @param body 原始响应体
     * @return 截断后的响应体
     */
    private String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= LOG_BODY_ABBREVIATE_LIMIT
                ? body : body.substring(0, LOG_BODY_ABBREVIATE_LIMIT) + LOG_BODY_TRUNCATE_SUFFIX;
    }
}
