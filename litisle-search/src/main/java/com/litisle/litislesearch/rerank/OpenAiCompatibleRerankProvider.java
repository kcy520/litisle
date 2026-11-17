package com.litisle.litislesearch.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchRerankConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 rerank 提供方（search 板块三 3.2 升级②主适配器）。
 *
 * 【适配范围】所有兼容 OpenAI 式 `/v1/rerank` 协议的服务：
 * SiliconFlow rerank（bge-reranker-v2-m3）、Jina/Cohere 兼容入口、自建 rerank 网关。
 * 【协议】请求 `{ "model": "<rerank.model>", "query": "<查询文本>", "documents": ["候选..."] }`；
 * 响应 `{ "results": [ { "index": 0, "relevance_score": 0.97 } ] }`（部分实现字段为 score，兼容解析）。
 * 【配置】provider=openai-compatible、base-url、model、api-key（可空）、timeout 全部外置。
 * 【失败语义】网络/解析/非 200 一律返回 null，由 RerankService 退回融合基线排序，结果不劣化。
 *
 * @author litisle
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = SearchRerankConstant.RERANK_PROPERTY_PREFIX,
        name = SearchRerankConstant.RERANK_PROPERTY_PROVIDER_NAME,
        havingValue = SearchRerankConstant.RERANK_PROVIDER_OPENAI_COMPATIBLE)
public class OpenAiCompatibleRerankProvider implements RerankProvider {

    /** OpenAI 兼容重排端点 */
    private static final String ENDPOINT_RERANK = "/v1/rerank";

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

    /** 请求字段：模型名 */
    private static final String REQUEST_FIELD_MODEL = "model";

    /** 请求字段：查询文本 */
    private static final String REQUEST_FIELD_QUERY = "query";

    /** 请求字段：候选文档数组 */
    private static final String REQUEST_FIELD_DOCUMENTS = "documents";

    /** 响应字段：结果数组 */
    private static final String RESPONSE_FIELD_RESULTS = "results";

    /** 响应字段：候选下标 */
    private static final String RESPONSE_FIELD_INDEX = "index";

    /** 响应字段：相关性分数（主流实现字段名） */
    private static final String RESPONSE_FIELD_RELEVANCE_SCORE = "relevance_score";

    /** 响应字段：相关性分数（部分兼容实现字段名，relevance_score 缺失时兜底） */
    private static final String RESPONSE_FIELD_SCORE = "score";

    /** search 配置（读取 litisle.search.rerank.* 提供方参数） */
    private final SearchProperties properties;

    /** JSON 序列化/反序列化（请求体与响应解析） */
    private final ObjectMapper objectMapper;

    /** 复用的 HTTP 客户端（连接超时取配置 timeout） */
    private final HttpClient httpClient;

    /**
     * 构造 OpenAI 兼容重排适配器。
     *
     * @param properties   search 配置
     * @param objectMapper JSON 处理器
     */
    public OpenAiCompatibleRerankProvider(SearchProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 连接超时取配置；配置为空时用兜底值（请求级超时另在发起请求时设置）
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(configTimeout())
                .build();
    }

    /**
     * 报出本适配器的名字。
     * 做什么：返回固定值 "openai-compatible"。
     * 怎么做：直接返回常量。配置项 rerank.provider 写的就是这个名字，
     * 上层按名字挑选重排服务时，靠这个方法对上号、选中本类。
     *
     * @return openai-compatible
     */
    @Override
    public String providerName() {
        return SearchRerankConstant.RERANK_PROVIDER_OPENAI_COMPATIBLE;
    }

    /**
     * 报出用的重排模型名。
     * 做什么：返回配置项 rerank.model 里写的模型名（比如 bge-reranker-v2-m3）。
     * 怎么做：直接读配置返回，主要用在日志里记录这次调用用的是哪个模型。
     *
     * @return 模型名
     */
    @Override
    public String modelName() {
        return properties.getRerank().getModel();
    }

    /**
     * 检查本适配器的配置是否齐全。
     * 做什么：回答「现在能不能发重排请求」。
     * 怎么做：只检查两件事——配置里的服务地址（base-url）和模型名（model）是不是都填了内容；
     * 两个都填了才返回 true。只看本地配置，不真的发网络请求去试，
     * 所以这个方法永远很快，不会拖慢查询。
     *
     * @return true=可以调用
     */
    @Override
    public boolean available() {
        return StringUtils.hasText(baseUrl()) && StringUtils.hasText(modelName());
    }

    /**
     * 调用重排服务，给每个候选文档打一个相关性分数。
     * 做什么：给一段查询文本和一批候选文档，返回和候选数量一样多的分数列表——
     * 第几个候选对应第几个分数，分数越高表示这个候选和查询文本越相关。
     * 怎么做：1）把「模型名 + 查询文本 + 候选文档数组」拼成 JSON 请求体；
     * 2）向配置的服务地址发 POST 请求（地址 + /v1/rerank），超时时间用配置里的 timeout；
     * 3）配置了密钥（api-key）就在请求头加 Bearer 令牌，没配置就不加；
     * 4）服务返回后解析出每个候选的分数（见 parseScores）。
     * 怎么处理失败：网络不通、请求超时、返回状态码不是 200、返回内容解析不了——
     * 这些情况都返回 null，表示这一次全部失败；上层拿到 null 会沿用原来的排序，
     * 所以这里不会抛异常打断正常查询。
     *
     * @param queryText 查询文本
     * @param documents 候选文档列表
     * @return 与输入等长的分数列表（下标一一对应，单条解析失败为 null）；整批失败返回 null
     */
    @Override
    public List<Double> rerank(String queryText, List<String> documents) {
        /*
         * 业务逻辑步骤：
         * 1. 组装 OpenAI 兼容协议请求体：模型名 + 查询文本 + 候选文档数组；
         * 2. 发送 POST /v1/rerank 请求，网络失败/非 200 时返回 null 整批降级；
         * 3. 解析响应 results 数组，结构异常（缺 results 或非数组）记日志后返回 null；
         * 4. 按 index 回填分数到与输入等长的结果列表（index 越界/分数缺失该项为 null，不抛错）；
         * 5. 解析抛异常同样记日志返回 null——本类任何失败都只影响本次重排，不阻断查询主链路。
         */

        // 协议请求体：模型 + 查询文本 + 候选文档数组（不传 top_n，返回全量分数便于服务侧统一排序截断）
        Map<String, Object> requestBody = Map.of(
                REQUEST_FIELD_MODEL, modelName(),
                REQUEST_FIELD_QUERY, queryText,
                REQUEST_FIELD_DOCUMENTS, documents);
        try {
            // 请求体序列化为 JSON（失败走统一降级）
            String jsonBody = objectMapper.writeValueAsString(requestBody);
            // 完整请求地址 = 规整后的 base-url + 重排端点
            String url = baseUrl() + ENDPOINT_RERANK;
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            // 请求级超时（配置为空时不设置，由客户端默认策略兜底）
            Duration timeout = properties.getRerank().getTimeout();
            if (timeout != null) {
                builder.timeout(timeout);
            }
            // 密钥非空才加 Bearer（内网自建 rerank 无鉴权场景不发送空令牌）
            String apiKey = properties.getRerank().getApiKey();
            if (StringUtils.hasText(apiKey)) {
                builder.header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey.trim());
            }

            // 同步发送请求，响应体按字符串读取
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
//            上面这行是真正把重排请求发出去的地方
//            做什么`httpClient.send(...)` —— 把前面准备好的 HTTP 请求发送给重排服务，然后 停在这里等对方返回 （同步等待，不是发完就不管）。拿到响应后存进`response` 变量。
//            发到哪 请求目标地址 =`builder.build()` 里带的信息，来自前面几行拼出来的`url` ：
//            送去干什么 让远端的 重排模型 （bge-reranker 这类打分模型）给每个候选打分——判断每篇候选文章的「标题+摘要」和查询文本有多相关，比如候选 1 打 0.97、候选 2 打 0.42。
//
//            一句话总结 ：这行 = 把「查询文本 + 所有候选文章」打包发给重排服务的打分接口，卡在这等它把每个候选的相关性分数算好寄回来；如果网络不通或对方没响应，会抛异常，被下面的`catch` 接住返回 null，查询退回原来的排序。
//
//            为什么不用api模块的feign远程调用？
//            一句话总结 ：api 模块的 Client = 自己人之间打电话的通讯录；rerank/embedding = 打给外部服务商，项目约定走「SPI 适配器 + 配置外置 + 自己管超时和降级」，两者分层不同，不混用。


//            bge-reranker 这类模型叫 cross-encoder（交叉编码器） ，打分逻辑一句话： 把「查询文本 + 候选文档」拼成一对，一起送进模型，直接输出一个相关程度分数 。
//            打分过程（三步）
//            1. 输入拼接：`[CLS] 查询文本 [SEP] 候选标题+摘要` ；
//            2. 整段送进 Transformer，逐层做词与词的交叉注意力；
//            3. 取最后一层`[CLS]` 位置的输出，过一个 sigmoid，得到 0~1 的相关性分数 ——越接近 1 越相关。

            // 非 200 视为失败：记录状态码与截断后的响应体，返回 null 整批降级
            if (response.statusCode() != HTTP_STATUS_OK) {
                log.warn("[rerank 精排] 提供方返回非 200：provider={}, url={}, status={}, body={}",
                        providerName(), url, response.statusCode(), abbreviate(response.body()));
                return null;
            }
            return parseScores(response.body(), documents.size());
        } catch (InterruptedException e) {
            // 恢复中断标记，避免吞掉线程中断信号
            Thread.currentThread().interrupt();
            log.warn("[rerank 精排] 提供方调用被中断：provider={}", providerName());
            return null;
        } catch (Exception e) {
            // 网络/序列化异常：整批降级为 null，由 RerankService 退回基线排序
            log.warn("[rerank 精排] 提供方调用失败：provider={}, model={}", providerName(), modelName(), e);
            return null;
        }
    }

    /**
     * 解析重排服务返回的 JSON，取出每个候选的分数。
     * 做什么：把服务返回的一段 JSON 文本，变成和候选数量一样多的分数列表。
     * 怎么做：1）先把 JSON 解析出来，取出里面的 results 数组；
     * 2）建一个和候选数量一样长、每个位置都是"空"的分数列表；
     * 3）逐条看 results：每条里有 index（表示这条分数属于第几个候选）和分数值
     * （优先读 relevance_score 字段，没有就读 score 字段），按 index 把分数填到列表对应位置；
     * 4）遇到 index 越界或者分数缺失/不是数字的条目，跳过不处理，对应位置保持"空"。
     * 怎么处理失败：JSON 根本解析不了，或者返回里没有 results 数组——返回 null，
     * 表示这次全部失败，由上层沿用原来的排序；解析得了就按上面规则能填多少填多少。
     *
     * @param responseBody 响应体原文
     * @param docCount     候选文档条数（结果列表长度基准）
     * @return 分数列表（下标与候选一一对应，缺失项为 null）；结构异常返回 null
     */
    private List<Double> parseScores(String responseBody, int docCount) {
        try {
            // 响应原文解析为 JSON 树
            JsonNode root = objectMapper.readTree(responseBody);
            // 结果数组节点（path 缺失时返回缺失节点而非抛异常，交给下方 isArray 判断）
            JsonNode results = root.path(RESPONSE_FIELD_RESULTS);
            // 响应结构异常：整批降级
            if (!results.isArray()) {
                log.warn("[rerank 精排] OpenAI 兼容响应缺少 results 数组：provider={}", providerName());
                return null;
            }
            // 与输入等长的分数列表，全部预置 null 占位（未回填项视为该候选打分失败）
            List<Double> scores = new ArrayList<>(docCount);
            for (int i = 0; i < docCount; i++) {
                scores.add(null);
            }
            // 逐条取 index + relevance_score（缺失时兜底 score 字段）按候选下标回填
            for (JsonNode item : results) {
                int index = item.path(RESPONSE_FIELD_INDEX).asInt(-1);
                // index 越界：跳过该条，防数组越界污染其余结果
                if (index < 0 || index >= docCount) {
                    continue;
                }
                JsonNode scoreNode = item.path(RESPONSE_FIELD_RELEVANCE_SCORE).isMissingNode()
                        ? item.path(RESPONSE_FIELD_SCORE) : item.path(RESPONSE_FIELD_RELEVANCE_SCORE);
                // 分数缺失/非数值：该项保持 null 占位
                if (scoreNode.isMissingNode() || !scoreNode.isNumber()) {
                    continue;
                }
                scores.set(index, scoreNode.asDouble());
            }
            return scores;
        } catch (Exception e) {
            // JSON 解析异常：整批降级为 null
            log.warn("[rerank 精排] OpenAI 兼容响应解析失败：provider={}", providerName(), e);
            return null;
        }
    }

    /**
     * 取配置的服务地址，并把末尾多余的斜杠去掉。
     * 做什么：返回拼接请求用的完整服务地址前半部分。
     * 怎么做：1）读配置项 rerank.base-url；没填内容就返回 null（上层会视为不可用）；
     * 2）填了就去掉两头空格，再把末尾的一个或多个斜杠删掉——
     * 因为本类拼请求地址时会自己接上 "/v1/rerank"，不删的话地址里会出现连续两个斜杠，请求会打错。
     *
     * @return 规整后的 base-url；未配置返回 null
     */
    private String baseUrl() {
        String configured = properties.getRerank().getBaseUrl();
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
     * 取 HTTP 连接的超时时间。
     * 做什么：返回建立网络连接时最多等多久。
     * 怎么做：读配置项 rerank.timeout；如果配置里没写（是空），就用默认的 10 秒——
     * 因为 HttpClient 建对象时这个值不能是空，必须有个数。
     *
     * @return 连接超时
     */
    private Duration configTimeout() {
        Duration timeout = properties.getRerank().getTimeout();
        return timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /**
     * 把响应文本截短，用于写日志。
     * 做什么：给一段文本（通常是服务返回的错误内容），返回适合写进日志的短版本。
     * 怎么做：1）传进来是 null 就返回空字符串；2）长度不超过 200 字符就原样返回；
     * 3）超过 200 字符就只保留前 200 个字符，后面加 "..." 表示被截断了——
     * 防止出错时把一大段响应原文整个写进日志。
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
