package com.litisle.litislesearch.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * search 模块观测指标埋点（批次八 8.2）。
 *
 * 【指标清单】
 * - litisle_search_requests_total{type, outcome}：检索请求数；outcome ∈ cache | es | fallback | degraded | error，
 *   与检索主链路的返回路径一一对应（cache=热点缓存命中、es=常规命中、fallback=空结果兜底、
 *   degraded=ES 不可用热门降级、error=ES 不可用且降级关闭/失败）
 * - litisle_search_es_errors_total{type}：ES 查询异常次数（配合降级计数区分「ES 故障」与「降级生效」）
 * - litisle_search_heat_flush_round_total：热度刷写轮次扫描到的待刷新键数
 * - litisle_search_heat_flush_docs_total{result}：热度刷写按条目累计的成功/失败条数
 * - litisle_search_index_dlq_total{originQueue}：索引同步死信数（配 error 日志形成告警留痕）
 *
 * 【降级语义】MeterRegistry 未装配（未接 Micrometer）时全方法静默空转，观测是增强件，
 * 缺指标不得阻断检索/刷写/死信消费主流程。
 *
 * 【标签基数纪律】type/outcome/result/originQueue 均为有限枚举值（检索类型枚举、固定路径、队列常量），
 * 禁止在调用点拼接用户输入或动态字符串，防 Prometheus 时间线基数膨胀。
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class SearchMetrics {

    /** 指标名：检索请求总数（标签 type/outcome） */
    private static final String METRIC_SEARCH_REQUESTS = "litisle_search_requests";

    /** 指标名：ES 查询异常数（标签 type） */
    private static final String METRIC_ES_ERRORS = "litisle_search_es_errors";

    /** 指标名：热度刷写扫描轮次（累计扫描到的待刷新键数） */
    private static final String METRIC_HEAT_FLUSH_ROUND = "litisle_search_heat_flush_round";

    /** 指标名：热度刷写条目数（标签 result=success/failure） */
    private static final String METRIC_HEAT_FLUSH_DOCS = "litisle_search_heat_flush_docs";

    /** 指标名：索引同步死信数（标签 originQueue=原队列） */
    private static final String METRIC_INDEX_DLQ = "litisle_search_index_dlq";

    /** 检索返回路径：热点查询缓存命中 */
    public static final String OUTCOME_CACHE = "cache";

    /** 检索返回路径：常规 ES 检索命中 */
    public static final String OUTCOME_ES = "es";

    /** 检索返回路径：空结果兜底（Embedding→KNN→HEAT 热门） */
    public static final String OUTCOME_FALLBACK = "fallback";

    /** 检索返回路径：ES 不可用热门降级（source=DEGRADED） */
    public static final String OUTCOME_DEGRADED = "degraded";

    /** 检索返回路径：ES 不可用且降级关闭/降级失败（返回 SEARCH_ES_UNAVAILABLE） */
    public static final String OUTCOME_ERROR = "error";

    /** 标签名：检索类型（article/book/user） */
    private static final String TAG_TYPE = "type";

    /** 标签名：检索返回路径 */
    private static final String TAG_OUTCOME = "outcome";

    /** 标签名：结果（success/failure） */
    private static final String TAG_RESULT = "result";

    /** 标签名：死信原队列 */
    private static final String TAG_ORIGIN_QUEUE = "originQueue";

    /** 结果标签值：成功 */
    private static final String RESULT_SUCCESS = "success";

    /** 结果标签值：失败 */
    private static final String RESULT_FAILURE = "failure";

    /** Micrometer 注册表提供器（可能不存在，取不到时指标静默跳过） */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * 记录一次检索请求。
     *
     * @param type    检索类型（article/book/user，取自 SearchTypeEnum code）
     * @param outcome 返回路径（本类 OUTCOME_* 常量）
     */
    public void recordSearch(String type, String outcome) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_SEARCH_REQUESTS, TAG_TYPE, type, TAG_OUTCOME, outcome).increment();
    }

    /**
     * 记录一次 ES 查询异常（不含参数校验等业务异常）。
     *
     * @param type 检索类型（article/book/user）
     */
    public void recordEsError(String type) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_ES_ERRORS, TAG_TYPE, type).increment();
    }

    /**
     * 记录一轮热度刷写扫描到的待刷新键数。
     *
     * @param scannedKeys 本轮扫描到的键数（0 时不计数，避免空闲周期产生无意义增量）
     */
    public void recordHeatFlushRound(int scannedKeys) {
        if (scannedKeys <= 0) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_HEAT_FLUSH_ROUND).increment(scannedKeys);
    }

    /**
     * 记录单个热度刷写分片的条目结果。
     *
     * @param docs    本分片待刷写条目数
     * @param success true=批量写入成功；false=批量执行异常（条目已回灌待下轮）
     */
    public void recordHeatFlushBatch(int docs, boolean success) {
        if (docs <= 0) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_HEAT_FLUSH_DOCS, TAG_RESULT, success ? RESULT_SUCCESS : RESULT_FAILURE)
                .increment(docs);
    }

    /**
     * 记录一条索引同步死信（与 IndexDlqConsumer 的 error 日志一起构成告警留痕）。
     *
     * @param originQueue 死信来源队列（x-death 解析，缺失时为 unknown）
     */
    public void recordIndexDlq(String originQueue) {
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(METRIC_INDEX_DLQ, TAG_ORIGIN_QUEUE, originQueue).increment();
    }
}
