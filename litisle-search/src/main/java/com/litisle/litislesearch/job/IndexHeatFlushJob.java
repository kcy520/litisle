package com.litisle.litislesearch.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.MgetResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.get.GetResult;
import co.elastic.clients.elasticsearch.core.mget.MultiGetResponseItem;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.domain.entity.ArticleHeatDoc;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.metrics.SearchMetrics;
import com.litisle.litislesearch.service.HeatPendingService;
import com.litisle.litislesearch.service.HotQueryCacheService;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 文章热度刷写定时任务（批次三 3.2，批次A A5/A7 定案）。
 *
 * 【链路】扫描 Redis 热度待刷新键 → 逐键原子取走（Lua HGETALL+DEL，GETDEL 语义）
 * → 回查 article_idx 现有计数 → 按 A5 公式重算 heat
 * → 按 `heat.flush.batch-size`(默认 1000) 分片、`heat.flush.concurrency`(默认 2) 并发
 * 执行 `_bulk` update + doc 局部更新（只改计数与 heat，禁 index 整篇替换，保住 vec_dense）。
 *
 * 【公式】heat = (w_like×like_cnt + w_comment×comment_cnt + w_view×view_cnt) × 时间衰减因子；
 * 衰减因子 = 0.5^(距今秒数 / 半衰期秒数)，权重与半衰期取自 `litisle.search.weight.heat.*`（Nacos 可调）。
 *
 * 【容错】
 * - 空键/无有效增量：跳过，不产生 ES 写
 * - 单键取走或解析异常：只跳过该键，不影响同批其他键
 * - 回查/批量执行异常：已取走的增量回灌 Redis（mergeBack），等下一轮重试，绝不静默丢数
 * - _bulk 逐条失败：失败条目单独回灌 + error 留痕，成功条目照常生效
 * - ES 抖动（整体异常）仅跳过本轮：fixedDelay 语义下不会堆积下一次调度
 *
 * 【与 3.1 的衔接】3.1 消费端只做 Redis 累加，pending 键 TTL 2h 兜底；
 * 本任务取走后 Redis 键即删除，失败回灌会重新建键并刷新 TTL，保证「取走—刷写—回灌」闭环。
 *
 * 【热点缓存失效（批次四接入）】本轮存在待刷新键时，刷写完成后全量清理 search:hotquery:*，
 * 防热度排序/加权结果在缓存 TTL 内继续返回旧榜；清理失败仅告警（缓存有 TTL 兜底，不阻断刷写）。
 */
@Slf4j
@Component
public class IndexHeatFlushJob {

    /** ES 客户端（mget 回查 + _bulk 局部更新） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（刷写周期/批量/并发 + heat 权重与半衰期） */
    private final SearchProperties properties;

    /** 热度待刷新键服务（扫描 / 原子取走 / 失败回灌） */
    private final HeatPendingService heatPendingService;

    /** 热点查询缓存服务（刷写完成后失效榜单类缓存，批次四接入） */
    private final HotQueryCacheService hotQueryCacheService;

    /** 观测指标埋点（批次八 8.2：刷写轮次/条目成功失败计数） */
    private final SearchMetrics searchMetrics;

    /** 刷写并发线程池（大小取 heat.flush.concurrency，任务级共享，避免每轮重复建池） */
    private final ExecutorService flushExecutor;

    /**
     * 构造任务并初始化刷写线程池。
     *
     * @param elasticsearchClient  ES 客户端
     * @param properties           search 配置
     * @param heatPendingService   热度待刷新键服务
     * @param hotQueryCacheService 热点查询缓存服务
     * @param searchMetrics        观测指标埋点
     */
    public IndexHeatFlushJob(ElasticsearchClient elasticsearchClient,
                             SearchProperties properties,
                             HeatPendingService heatPendingService,
                             HotQueryCacheService hotQueryCacheService,
                             SearchMetrics searchMetrics) {
        this.elasticsearchClient = elasticsearchClient;
        this.properties = properties;
        this.heatPendingService = heatPendingService;
        this.hotQueryCacheService = hotQueryCacheService;
        this.searchMetrics = searchMetrics;
        // 并发度做下限防御：配置非法（0/负数）时退化为单线程，避免建池失败
        int concurrency = Math.max(1, properties.getHeat().getFlush().getConcurrency());
        this.flushExecutor = Executors.newFixedThreadPool(concurrency);
    }

    /**
     * 停机时释放线程池（容器关闭时调用，避免线程泄漏）。
     */
    @PreDestroy
    public void shutdown() {
        flushExecutor.shutdown();
    }

    /**
     * 定时刷写入口：fixedDelay 语义（上一轮结束后再等间隔，天然不堆积）。
     *
     * <p>周期与首次延迟取自 yml：litisle.search.heat.flush.interval-ms / initial-delay-ms。</p>
     */
    @Scheduled(fixedDelayString = "${litisle.search.heat.flush.interval-ms:900000}",
            initialDelayString = "${litisle.search.heat.flush.initial-delay-ms:60000}")
    public void flushHeat() {
        // 步骤1：扫描本窗口全部待刷新键；为空直接跳过（绝大多数空闲周期走这条路径）
        List<String> keys;
        try {
            keys = heatPendingService.scanPendingKeys();
        } catch (Exception e) {
            log.error("[热度刷写] 扫描待刷新键失败，本轮跳过（pending 键有 TTL 兜底不会丢）", e);
            return;
        }
        if (keys.isEmpty()) {
            log.debug("[热度刷写] 本窗口无待刷新热度，跳过");
            return;
        }
        // 指标：本轮扫描到的待刷新键数（空闲周期已在上面提前返回，不产生无意义增量）
        searchMetrics.recordHeatFlushRound(keys.size());

        // 步骤2：按批量上限切片，提交到固定并发池并行执行（单批过大易被 ES 拒绝）
        int batchSize = Math.max(1, properties.getHeat().getFlush().getBatchSize());
        List<Future<?>> futures = new ArrayList<>();
        for (int from = 0; from < keys.size(); from += batchSize) {
            int to = Math.min(from + batchSize, keys.size());
            // subList 视图只读使用，任务内不再修改原 keys
            List<String> chunk = keys.subList(from, to);
            futures.add(flushExecutor.submit(() -> flushChunk(chunk)));
        }

        // 步骤4：等待全部分片完成（单个分片异常已在 flushChunk 内消化并回灌，不向上扩散）
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.error("[热度刷写] 分片任务执行异常（增量已在分片内回灌）", e);
            }
        }

        // 步骤5：刷写完成后失效热点查询缓存（批次四接入）：
        // 放在「刷写完成后」保证下一次检索读到的 heat 已更新；失败仅告警，缓存 TTL 兜底
        try {
            hotQueryCacheService.evictAll();
        } catch (Exception e) {
            log.error("[热度刷写] 热点查询缓存失效失败（缓存有 TTL 兜底，不阻断本轮刷写）", e);
        }

        log.info("[热度刷写] 本轮完成：扫描键数={}，分片数={}，批量上限={}", keys.size(), futures.size(), batchSize);
    }

    /**
     * 刷写单个分片：取走 → 回查 → 重算 → 批量局部更新（失败回灌）。
     *
     * @param keys 本分片的待刷新键
     */
    private void flushChunk(List<String> keys) {
        // 步骤1：逐键原子取走；单键异常只跳过该键，不影响同批其他键
        List<PendingHeat> taken = new ArrayList<>();
        for (String key : keys) {
            try {
                Map<String, String> hash = heatPendingService.takePending(key);
                if (hash.isEmpty()) {
                    // 空键：上一轮已取走或 TTL 过期，直接跳过
                    log.debug("[热度刷写] 待刷新键为空，跳过：key={}", key);
                    continue;
                }
                PendingHeat pending = parsePending(key, hash);
                if (pending != null) {
                    taken.add(pending);
                }
            } catch (Exception e) {
                log.error("[热度刷写] 取走待刷新键失败，本轮跳过该键：key={}", key, e);
            }
        }
        if (taken.isEmpty()) {
            return;
        }

        // 步骤2：回查现有文档（失败则整批回灌，等下一轮）
        Map<String, ArticleIndexDoc> currentDocs;
        try {
            currentDocs = loadCurrentDocs(taken);
        } catch (Exception e) {
            log.error("[热度刷写] 回查文章文档失败，回灌本批增量等下一轮：size={}", taken.size(), e);
            restoreAll(taken);
            return;
        }

        // 步骤3：按 A5 公式计算新计数与 heat（文档已不存在的跳过，视为已下架）
        List<HeatUpdate> updates = buildUpdates(taken, currentDocs, LocalDateTime.now());
        if (updates.isEmpty()) {
            return;
        }

        // 步骤4：执行 _bulk 局部更新；整体异常则整批回灌（ES 抖动仅跳过本轮）
        try {
            bulkUpdate(updates);
            // 指标：本分片条目刷写成功（部分失败由 bulkUpdate 内部回灌并 error 留痕）
            searchMetrics.recordHeatFlushBatch(updates.size(), true);
        } catch (Exception e) {
            // 指标：本分片整体失败（条目已回灌，下一轮重试）
            searchMetrics.recordHeatFlushBatch(updates.size(), false);
            log.error("[热度刷写] _bulk 执行异常，回灌本批增量等下一轮：size={}", updates.size(), e);
            for (HeatUpdate update : updates) {
                restore(update);
            }
        }
    }

    /**
     * 解析待刷新键内容为增量对象（无有效增量时返回 null）。
     *
     * @param key  待刷新键（前缀 + 文章 ID）
     * @param hash Hash 字段内容
     * @return 待刷写增量；键格式非法或三类增量均缺失时返回 null
     */
    private PendingHeat parsePending(String key, Map<String, String> hash) {
        // 文章 ID 取键前缀之后的部分（服务端生成，无用户可控原串）
        String articleIdText = key.substring(SearchBizConstant.HEAT_PENDING_KEY_PREFIX.length());
        if (articleIdText.isBlank()) {
            log.warn("[热度刷写] 待刷新键缺少文章 ID，跳过：key={}", key);
            return null;
        }
        // 键内 ID 必须是数字：解析失败说明键格式异常，直接丢弃该键
        Long articleId = parseNullableLong(articleIdText, articleIdText, "article_id");
        if (articleId == null) {
            log.warn("[热度刷写] 待刷新键文章 ID 非法，跳过：key={}", key);
            return null;
        }
        // 解析三类增量：非法数字按 0 处理并告警（不因单字段脏数据中断整条刷写）
        long likeDelta = parseLong(hash.get(SearchBizConstant.HEAT_PENDING_FIELD_LIKE), articleIdText, "like_delta");
        long commentDelta = parseLong(hash.get(SearchBizConstant.HEAT_PENDING_FIELD_COMMENT), articleIdText, "comment_delta");
        Long viewCount = parseNullableLong(hash.get(SearchBizConstant.HEAT_PENDING_FIELD_VIEW), articleIdText, "view_cnt");
        // 三项均无有效值：无需重算 heat，直接丢弃（避免空写放大）
        if (likeDelta == 0 && commentDelta == 0 && viewCount == null) {
            log.debug("[热度刷写] 待刷新键无有效增量，跳过：articleId={}", articleIdText);
            return null;
        }
        return new PendingHeat(articleId, likeDelta, commentDelta, viewCount);
    }

    /**
     * 回查 article_idx 现有文档（一次 mget 完成本分片全部文章）。
     *
     * @param pendings 本分片的待刷写增量
     * @return 文章 ID → 现有文档（不存在的文章不在 Map 中）
     * @throws Exception ES 访问异常（由调用方回灌整批增量）
     */
    private Map<String, ArticleIndexDoc> loadCurrentDocs(List<PendingHeat> pendings) throws Exception {
        String articleIndex = properties.getIndex().getArticle();
        List<String> ids = pendings.stream().map(p -> String.valueOf(p.getArticleId())).collect(Collectors.toList());
        // mget：一次请求取回全部目标文档，避免逐篇 get 放大往返
        MgetResponse<ArticleIndexDoc> response = elasticsearchClient.mget(m -> m.index(articleIndex).ids(ids),
                ArticleIndexDoc.class);
        Map<String, ArticleIndexDoc> docs = new HashMap<>();
        for (MultiGetResponseItem<ArticleIndexDoc> item : response.docs()) {
            // mget 单条结果可能为 null（查询失败/文档缺失），按不存在处理
            GetResult<ArticleIndexDoc> result = item.result();
            if (result == null || !result.found()) {
                // 文档不存在：可能已下架/未索引，buildUpdates 中按跳过处理
                continue;
            }
            ArticleIndexDoc doc = result.source();
            if (doc == null) {
                continue;
            }
            // 以 mget 返回的文档主键为 Map 键，保持与 Redis 待刷新键的文章 ID 对齐
            docs.put(result.id(), doc);
        }
        return docs;
    }

    /**
     * 按 A5 公式批量计算新计数与 heat。
     *
     * @param pendings    本分片增量
     * @param currentDocs 现有文档（mget 结果）
     * @param now         当前时间（时间衰减基准）
     * @return 待更新的局部文档列表（文档已不存在的条目被跳过）
     */
    private List<HeatUpdate> buildUpdates(List<PendingHeat> pendings, Map<String, ArticleIndexDoc> currentDocs,
                                          LocalDateTime now) {
        // 公式权重与半衰期取自配置，Nacos 可调
        SearchProperties.Weight.Heat heatConfig = properties.getWeight().getHeat();
        List<HeatUpdate> updates = new ArrayList<>();
        for (PendingHeat pending : pendings) {
            ArticleIndexDoc current = currentDocs.get(String.valueOf(pending.getArticleId()));
            if (current == null) {
                // 文档不在了（已下架/尚未索引）：热度无意义，丢弃本键增量并留痕
                log.debug("[热度刷写] 文章索引文档不存在，跳过：articleId={}", pending.getArticleId());
                continue;
            }
            // 计数基数为「现有绝对值 + 本窗口增量」；浏览量以事件落库绝对值优先，未收到则沿用现有值
            long likeCnt = nullToZero(current.getLikeCnt()) + pending.getLikeDelta();
            long commentCnt = nullToZero(current.getCommentCnt()) + pending.getCommentDelta();
            long viewCnt = pending.getViewCount() != null ? pending.getViewCount() : nullToZero(current.getViewCnt());
            // 时间衰减：以文档 updateTime（当前实现中兼具发布时间语义）为基准，缺失时不做衰减
            double decay = decayFactor(current.getUpdateTime(), heatConfig.getTimeDecayHalfLife(), now);
            double heat = (heatConfig.getLike() * likeCnt
                    + heatConfig.getComment() * commentCnt
                    + heatConfig.getView() * viewCnt) * decay;

            // 只组热度相关字段（update+doc 局部更新，绝不整篇 index）
            ArticleHeatDoc heatDoc = new ArticleHeatDoc();
            heatDoc.setLikeCnt(likeCnt);
            heatDoc.setCommentCnt(commentCnt);
            heatDoc.setViewCnt(viewCnt);
            heatDoc.setHeat(heat);
            updates.add(new HeatUpdate(pending, heatDoc));
        }
        return updates;
    }

    /**
     * 执行 _bulk 局部更新，逐条校验结果并对失败条目回灌。
     *
     * @param updates 待更新条目
     * @throws Exception ES 整体异常（由调用方整批回灌）
     */
    private void bulkUpdate(List<HeatUpdate> updates) throws Exception {
        String articleIndex = properties.getIndex().getArticle();
        // 构建 _bulk：update + doc，只提交热度字段，禁止 index 整篇替换
        BulkRequest.Builder builder = new BulkRequest.Builder();
        for (HeatUpdate update : updates) {
            // 8.x 客户端把 doc 放在 action 子构建器：update + action(doc) 即局部更新
            builder.operations(op -> op.update(u -> u
                    .index(articleIndex)
                    .id(String.valueOf(update.getPending().getArticleId()))
                    .action(a -> a.doc(update.getHeatDoc()))));
        }
        BulkResponse response = elasticsearchClient.bulk(builder.build());
        if (!response.errors()) {
            return;
        }
        // 逐条失败留痕，并单独回灌失败条目（成功条目无需回灌）
        Set<String> failedIds = new HashSet<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                continue;
            }
            failedIds.add(item.id());
            log.error("[热度刷写] 单条更新失败：articleId={}, status={}, reason={}",
                    item.id(), item.status(), item.error().reason());
        }
        for (HeatUpdate update : updates) {
            if (failedIds.contains(String.valueOf(update.getPending().getArticleId()))) {
                restore(update);
            }
        }
        log.warn("[热度刷写] 批量部分失败：总数={}，失败数={}，失败条目已回灌待下轮重试",
                updates.size(), failedIds.size());
    }

    /**
     * 回灌单条未成功写入的增量。
     *
     * @param update 更新条目（含原始增量快照）
     */
    private void restore(HeatUpdate update) {
        PendingHeat pending = update.getPending();
        heatPendingService.mergeBack(pending.getArticleId(), pending.getLikeDelta(),
                pending.getCommentDelta(), pending.getViewCount());
    }

    /**
     * 回灌整批增量（回查或整体批量失败时使用）。
     *
     * @param pendings 待回灌条目
     */
    private void restoreAll(List<PendingHeat> pendings) {
        for (PendingHeat pending : pendings) {
            heatPendingService.mergeBack(pending.getArticleId(), pending.getLikeDelta(),
                    pending.getCommentDelta(), pending.getViewCount());
        }
    }

    /**
     * 计算时间衰减因子（半衰期折半）。
     *
     * @param updateTime 文档更新时间（当前实现中兼具发布时间语义；为空时不做衰减）
     * @param halfLife   半衰期
     * @param now        当前时间
     * @return 衰减因子（0~1]
     */
    private double decayFactor(LocalDateTime updateTime, Duration halfLife, LocalDateTime now) {
        if (updateTime == null) {
            // 缺失时间基准：按不衰减处理，避免 heat 全量归零
            return 1.0;
        }
        // 半衰期做下限防御：配置为 0/负数时退化为 1 秒，避免除零
        long halfLifeSeconds = Math.max(1, halfLife.toSeconds());
        // 文档时间晚于当前时间（时钟偏差）时按 0 岁处理
        long ageSeconds = Math.max(0, Duration.between(updateTime, now).toSeconds());
        return Math.pow(0.5, (double) ageSeconds / halfLifeSeconds);
    }

    /**
     * 解析非空 Long（缺失或非法时按 0 处理并告警）。
     *
     * @param value     原始字符串
     * @param articleId 文章 ID（日志定位）
     * @param field     字段名（日志定位）
     * @return 解析结果，异常时 0
     */
    private long parseLong(String value, String articleId, String field) {
        Long parsed = parseNullableLong(value, articleId, field);
        return parsed == null ? 0L : parsed;
    }

    /**
     * 解析可空 Long（缺失返回 null，非法值告警后返回 null）。
     *
     * @param value     原始字符串
     * @param articleId 文章 ID（日志定位）
     * @param field     字段名（日志定位）
     * @return 解析结果或 null
     */
    private Long parseNullableLong(String value, String articleId, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            // 脏数据不中断整条刷写：告警后按缺失处理
            log.warn("[热度刷写] 待刷新键字段非法，按缺失处理：articleId={}, field={}, value={}", articleId, field, value);
            return null;
        }
    }

    /**
     * 空 Long 转 0（计数基数缺失按 0 起算）。
     *
     * @param value 原值
     * @return 非空值或 0
     */
    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 待刷写增量（一个 pending 键解析后的业务快照，也作为回灌依据）。
     */
    @Getter
    @RequiredArgsConstructor
    private static class PendingHeat {
        /** 文章 ID（ES 文档主键） */
        private final Long articleId;
        /** 窗口内点赞增量 */
        private final long likeDelta;
        /** 窗口内评论增量 */
        private final long commentDelta;
        /** 浏览量绝对值（null 表示本条无浏览量） */
        private final Long viewCount;
    }

    /**
     * 单条刷写任务（增量快照 + 待写入的局部文档），回灌时按 articleId 匹配失败条目。
     */
    @Getter
    @RequiredArgsConstructor
    private static class HeatUpdate {
        /** 原始增量快照（回灌依据） */
        private final PendingHeat pending;
        /** 待写入的局部文档（计数绝对值 + heat） */
        private final ArticleHeatDoc heatDoc;
    }
}
