package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.UpdateAction;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.domain.entity.ArticleChunkDoc;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.service.VectorRebuildService;
import com.litisle.litislesearch.service.VectorService;
import com.litisle.litislesearch.utils.SearchTextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量全量重建实现（批次七）。
 *
 * 【流程】可用性检查（无提供方整体跳过）→ search_after 分页扫描（按主键升序，避免深分页窗口限制）
 * → 逐条向量化 → bulk partial update 只写向量字段。
 * 【口径】文章向量输入与发布写入共用 {@link SearchTextUtils#buildEmbeddingInput}；
 * 切片向量直接使用 chunk_text 原文，保证重建结果与事件链路一致。
 * 【元信息】单类重建成功后把两索引 `_meta.embedding_model`/`embedding_dims` 更新为配置中的真实
 * 模型名与维度（批次七选型定案），便于运维确认当前索引向量语义版本。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorRebuildServiceImpl implements VectorRebuildService {

    /** ES 客户端（分页扫描 + 批量局部更新） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（索引名 + 重建扫描批量） */
    private final SearchProperties properties;

    /** 向量化服务（提供方统一入口；未就绪时整体跳过重建） */
    private final VectorService vectorService;

    /**
     * 重建文章索引向量。
     *
     * @return 成功更新条数
     */
    @Override
    public int rebuildArticleVectors() {
        // 提供方未就绪（选型未确认/开关关闭）：整体跳过，避免逐条空跑与无效 ES 写
        if (!vectorService.available()) {
            log.warn("[向量重建] Embedding 提供方未就绪，跳过 article_idx 重建");
            return 0;
        }
        String index = properties.getIndex().getArticle();
        int batch = scanBatchSize();
        int updated = 0;
        List<FieldValue> cursor = null;
        while (true) {
            SearchResponse<ArticleIndexDoc> response = scan(index, batch, cursor, ArticleIndexDoc.class);
            List<Hit<ArticleIndexDoc>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                break;
            }
            List<BulkOperation> operations = new ArrayList<>();
            for (Hit<ArticleIndexDoc> hit : hits) {
                ArticleIndexDoc doc = hit.source();
                // 脏数据（缺主键）跳过，不让单条文档阻断整批重建
                if (doc == null || !StringUtils.hasText(doc.getId())) {
                    continue;
                }
                List<Float> vector = vectorService.embed(
                        SearchTextUtils.buildEmbeddingInput(doc.getTitle(), doc.getSummary(), doc.getContent()));
                // 单条向量化失败（提供方抖动/维度不符）：跳过该条，后续可重跑补齐
                if (vector == null) {
                    log.warn("[向量重建] 单条文章向量化失败，跳过：articleId={}", doc.getId());
                    continue;
                }
                operations.add(updateVectorOperation(index, doc.getId(), SearchBizConstant.FIELD_VEC_DENSE, vector));
            }
            // 批量更新只写向量字段，其余字段（heat/content/tags 等）保持原值
            updated += bulkUpdate(operations, "article_idx");
            // 不足一页说明已到末尾
            if (hits.size() < batch) {
                break;
            }
            // 下一页游标：最后一条的排序值（主键）
            cursor = hits.get(hits.size() - 1).sort();
            if (cursor == null || cursor.isEmpty()) {
                log.warn("[向量重建] 扫描游标缺失，提前结束 article_idx 重建");
                break;
            }
        }
        log.info("[向量重建] article_idx 完成：updated={}", updated);
        // 重建成功后同步索引 _meta，登记当前向量语义版本（失败仅告警，不影响已重建数据）
        updateEmbeddingMeta();
        return updated;
    }

    /**
     * 重建切片索引向量。
     *
     * @return 成功更新条数
     */
    @Override
    public int rebuildChunkVectors() {
        // 提供方未就绪：整体跳过（与文章重建同一可用性口径）
        if (!vectorService.available()) {
            log.warn("[向量重建] Embedding 提供方未就绪，跳过 article_chunks 重建");
            return 0;
        }
        String index = properties.getIndex().getChunk();
        int batch = scanBatchSize();
        int updated = 0;
        List<FieldValue> cursor = null;
        while (true) {
            SearchResponse<ArticleChunkDoc> response = scan(index, batch, cursor, ArticleChunkDoc.class);
            List<Hit<ArticleChunkDoc>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                break;
            }
            List<BulkOperation> operations = new ArrayList<>();
            for (Hit<ArticleChunkDoc> hit : hits) {
                ArticleChunkDoc doc = hit.source();
                // 脏数据（缺切片主键/正文）跳过
                if (doc == null || !StringUtils.hasText(doc.getChunkId())
                        || !StringUtils.hasText(doc.getChunkText())) {
                    continue;
                }
                List<Float> vector = vectorService.embed(doc.getChunkText());
                // 单条失败跳过，后续可重跑补齐
                if (vector == null) {
                    log.warn("[向量重建] 单条切片向量化失败，跳过：chunkId={}", doc.getChunkId());
                    continue;
                }
                operations.add(updateVectorOperation(index, doc.getChunkId(),
                        SearchBizConstant.FIELD_CHUNK_VECTOR, vector));
            }
            updated += bulkUpdate(operations, "article_chunks");
            if (hits.size() < batch) {
                break;
            }
            cursor = hits.get(hits.size() - 1).sort();
            if (cursor == null || cursor.isEmpty()) {
                log.warn("[向量重建] 扫描游标缺失，提前结束 article_chunks 重建");
                break;
            }
        }
        log.info("[向量重建] article_chunks 完成：updated={}", updated);
        // 重建成功后同步索引 _meta（与文章索引同一口径）
        updateEmbeddingMeta();
        return updated;
    }

    /**
     * 把两索引的 `_meta.embedding_model` / `_meta.embedding_dims` 更新为配置中的真实值。
     *
     * <p>只写 meta 不动字段 mapping（`_meta` 为只读元信息，putMapping 幂等）；
     * 失败仅告警：向量数据已重建成功，meta 登记失败不阻断运维流程，可稍后重跑一次重建刷新。</p>
     */
    private void updateEmbeddingMeta() {
        String model = properties.getEmbedding().getModel();
        // 模型未配置时不写 meta，避免把空值/占位值误登记为真实模型
        if (!StringUtils.hasText(model)) {
            log.warn("[向量重建] embedding.model 未配置，跳过索引 _meta 更新");
            return;
        }
        int dimension = properties.getEmbedding().getDimension();
        for (String index : List.of(properties.getIndex().getArticle(), properties.getIndex().getChunk())) {
            try {
                // 只更新 _meta 元信息：模型名 + 维度（JsonData 包装，保持索引元信息类型一致）
                Map<String, JsonData> meta = Map.of(
                        SearchBizConstant.META_EMBEDDING_MODEL, JsonData.of(model),
                        SearchBizConstant.META_EMBEDDING_DIMS, JsonData.of(dimension));
                // putMapping + meta：ES 对已存在索引幂等，可重复执行
                elasticsearchClient.indices().putMapping(p -> p.index(index).meta(meta));
                log.info("[向量重建] 索引 _meta 已更新：index={}, model={}, dims={}", index, model, dimension);
            } catch (Exception e) {
                // meta 更新失败仅告警（重建数据已生效，可人工重跑刷新）
                log.error("[向量重建] 索引 _meta 更新失败（不影响已重建向量）：index={}", index, e);
            }
        }
    }

    /**
     * 分页扫描一页文档（search_after 按主键升序，规避 from+size 深分页窗口限制）。
     *
     * @param index    索引名
     * @param batch    每批条数
     * @param cursor   上一批最后一条的排序值（首批为 null）
     * @param docClass 文档映射类型
     * @param <T>      文档映射泛型
     * @return 本页命中
     */
    private <T> SearchResponse<T> scan(String index, int batch, List<FieldValue> cursor, Class<T> docClass) {
        try {
            // 按主键升序排序，作为 search_after 游标（keyword 字段可直接排序）
            SortOptions idAsc = SortOptions.of(s -> s.field(f -> f
                    .field(SearchBizConstant.FIELD_ID)
                    .order(SortOrder.Asc)));
            SearchRequest.Builder builder = new SearchRequest.Builder()
                    .index(index)
                    .size(batch)
                    // 仅公开文档参与重建（与写入侧 PUBLIC 闸门一致）
                    .query(termFilter(SearchBizConstant.FIELD_VISIBILITY, SearchBizConstant.VISIBILITY_PUBLIC))
                    .sort(List.of(idAsc));
            if (cursor != null && !cursor.isEmpty()) {
                builder.searchAfter(cursor);
            }
            return elasticsearchClient.search(builder.build(), docClass);
        } catch (Exception e) {
            // 扫描失败直接抛出：全量重建属运维动作，失败必须显式暴露，不能静默半途而废
            throw new IllegalStateException("向量重建扫描失败（ES 异常），index=" + index, e);
        }
    }

    /**
     * 构造「只更新向量字段」的 bulk 操作。
     *
     * @param index  索引名
     * @param id     文档主键
     * @param field  向量字段名（vec_dense / chunk_vector）
     * @param vector 向量
     * @return bulk 操作
     */
    private BulkOperation updateVectorOperation(String index, String id, String field, List<Float> vector) {
        // update + doc：局部更新，避免整篇替换导致其他字段丢失
        // 注意：doc 位于 UpdateAction.Builder（UpdateOperation.Builder 只承载 index/id），故先构造 action 再挂载
        UpdateAction<Object, Object> action = UpdateAction.of(builder -> builder
                .doc(Map.of(field, vector)));
        return BulkOperation.of(op -> op.update(update -> update
                .index(index)
                .id(id)
                .action(action)));
    }

    /**
     * 执行批量更新并校验逐条结果。
     *
     * @param operations bulk 操作列表
     * @param context    日志上下文（索引语义名）
     * @return 成功条数
     */
    private int bulkUpdate(List<BulkOperation> operations, String context) {
        // 空批次直接返回（尾页可能没有可更新文档）
        if (operations.isEmpty()) {
            return 0;
        }
        try {
            BulkResponse response = elasticsearchClient.bulk(b -> b.operations(operations));
            int success = 0;
            for (BulkResponseItem item : response.items()) {
                if (item.error() == null) {
                    success++;
                } else {
                    // 逐条留痕，便于定位是文档主键问题还是向量字段问题
                    log.error("[向量重建] 批量更新失败：context={}, id={}, reason={}",
                            context, item.id(), item.error().reason());
                }
            }
            // 任一条失败即抛出：重建结果必须完整，调用方重跑即可补齐
            if (response.errors()) {
                throw new IllegalStateException("向量重建批量更新部分失败，context=" + context);
            }
            return success;
        } catch (IllegalStateException e) {
            // 业务性失败原样抛出（保留上面的 context 信息）
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("向量重建批量更新失败（ES 异常），context=" + context, e);
        }
    }

    /**
     * 扫描批量（配置非法时回退默认 100）。
     *
     * @return 合法批量
     */
    private int scanBatchSize() {
        return Math.max(1, properties.getEmbedding().getRebuildBatchSize());
    }

    /**
     * 构造 term 精确过滤。
     *
     * @param field 字段名（索引常量）
     * @param value 过滤值
     * @return 查询 DSL
     */
    private Query termFilter(String field, String value) {
        return TermQuery.of(t -> t.field(field).value(FieldValue.of(value)))._toQuery();
    }
}
