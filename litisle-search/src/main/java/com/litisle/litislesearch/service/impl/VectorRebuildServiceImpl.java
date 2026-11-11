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
import com.litisle.litislesearch.config.SearchProperties.Embedding;
import com.litisle.litislesearch.constant.SearchFieldConstant;
import com.litisle.litislesearch.constant.SearchGateConstant;
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


// ## 向量重建链路总结（VectorRebuildServiceImpl）

// ### 它解决什么问题
// ES 里的向量字段（`vec_dense` /`chunk_vector` ）是由 当时的 Embedding 模型 生成的。一旦模型选型变更、或某段时间向量化服务故障导致漏写，索引里的向量就会与新模型语义不一致。这个类是 运维动作 ：把两个索引的全部公开文档重新向量化一遍，让向量与当前配置的模型对齐。

// ### 两条重建链路（结构完全对称）
// rebuildArticleVectors（article_idx）
//   输入 = 标题+摘要+正文（buildEmbeddingInput，与发布事件链路同一构造）
//   写 vec_dense 字段

// rebuildChunkVectors（article_chunks）
//   输入 = chunk_text 原文（与切片写入链路同一口径）
//   写 chunk_vector 字段

// 口径统一是核心设计 ：重建输入与事件写入输入共用同一构造方法，保证“重建出来的向量”和“实时写入的向量”语义一致——否则重建反而会制造新旧混杂。

// ### 单条链路的五步
// ① 可用性检查    提供方未就绪（模型未选型/开关关）→ 返回 0 整体跳过（降级，不算失败）
// ② 分页扫描      search_after 按主键升序逐页翻（规避 from+size 深分页窗口限制）
//                 只扫 visibility=PUBLIC（与写入侧闸门一致）
// ③ 逐条向量化    单条失败（提供方抖动/维度不符）→ 只跳过该条，重跑可补齐
// ④ 批量局部更新  每页组一队 _bulk，update+doc 只写向量字段（保住 heat/content 等其他字段）
// ⑤ 登记版本      重建成功后把 _meta.embedding_model / embedding_dims 写进两索引
//                 （失败仅告警，可重跑刷新——数据已生效，meta 只是运维标注）

// 全量扫描 + 逐页处理（页内逐条向量化，页末批量局部更新）+ 全程幂等可重跑

// 一轮循环（处理一页，batch=100）：
// ┌─ scan 取一页 ────────────→ 对应"全量扫描/一页一页扫描"（第②步）
// ├─ for 循环逐条 embed ──────→ 对应"页内逐条向量化"（第③步）
// ├─ _bulk 一次提交 100 条 ───→ 这就是"批量局部更新"（第④步）★
// └─ 推进游标，进入下一页

// - 你那句话（全量扫描 + 逐页处理 + 逐条向量化）概括的是 数据怎么进 ——②③ 两步；
// - “批量局部更新”说的是 数据怎么出 ——每页向量算完后，把 100 条更新打包成一次`_bulk` 提交（ L149 、 L229 ）；
// - “逐页处理”这个词宽泛讲可以涵盖页末的批量提交（提交是“处理这一页”的最后一个动作），但你这句概括的重点落在扫描与向量化上，所以读起来两者像是并列的两件事。
  

    /** ES 客户端（分页扫描 + 批量局部更新） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（索引名 + 重建扫描批量） */
    private final SearchProperties properties;

    /** 向量化服务（提供方统一入口；未就绪时整体跳过重建） */
    private final VectorService vectorService;

    /**
     * 重建文章索引向量：分页扫描公开文章 → 逐条向量化 → bulk 局部更新 vec_dense。
     *
     * @return 成功更新条数；Embedding 提供方未就绪时返回 0（整体跳过，属降级而非失败）
     */
    @Override
    public int rebuildArticleVectors() {
        /*
         * 业务逻辑步骤：
         * 1. 可用性检查：提供方未就绪则整体跳过，避免逐条空跑与无效 ES 写；
         * 2. search_after 循环分页扫描公开文章（按主键升序推进游标，规避深分页限制）；
         * 3. 逐条用与发布链路相同的输入构造（标题+摘要+正文）向量化，单条失败只跳过该条；
         * 4. 每页把成功向量组成本批 bulk 局部更新（只写 vec_dense，不动其他字段）；
         * 5. 页不满说明已扫到末尾，循环结束后同步索引 _meta 登记向量语义版本。
         */
        // 提供方未就绪（选型未确认/开关关闭）：整体跳过，避免逐条空跑与无效 ES 写
        if (!vectorService.available()) {
            log.warn("[向量重建] Embedding 提供方未就绪，跳过 article_idx 重建");
            return 0;
        }
        // 变量：文章索引名（与写入链路同一索引）
        String index = properties.getIndex().getArticle();
        // 调用 scanBatchSize 取扫描批量（配置非法时兜底 1，保证分页可推进）
        int batch = scanBatchSize();
        // 变量：累计成功更新条数（方法返回值）
        int updated = 0;
        // 变量：search_after 游标（null 表示第一页；此后每页取上一页最后一条的排序值续扫）
        List<FieldValue> cursor = null;
        // 步骤2：循环扫描直到空页/不满页（每轮处理一页；页内失败仅影响单条，不中断整轮）
        while (true) {
            // 调用 scan 按游标扫描一页（失败抛异常中止重建，运维动作不静默）
            SearchResponse<ArticleIndexDoc> response = scan(index, batch, cursor, ArticleIndexDoc.class);
            // 变量：本页命中列表
            List<Hit<ArticleIndexDoc>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                break;
            }
            // 变量：本页待执行的 bulk 操作集合（逐条构造，页末统一提交）
            List<BulkOperation> operations = new ArrayList<>();
            for (Hit<ArticleIndexDoc> hit : hits) {
                // 变量：本条文章文档源数据
                ArticleIndexDoc doc = hit.source();
                // 脏数据（缺主键）跳过，不让单条文档阻断整批重建
                if (doc == null || !StringUtils.hasText(doc.getId())) {
                    continue;
                }
                // 调用向量化：输入用与发布链路同一构造（标题+摘要+正文），保证重建与事件写入口径一致
                List<Float> vector = vectorService.embed(
                        SearchTextUtils.buildEmbeddingInput(doc.getTitle(), doc.getSummary(), doc.getContent()));
                // 单条向量化失败（提供方抖动/维度不符）：跳过该条，后续可重跑补齐
                if (vector == null) {
                    log.warn("[向量重建] 单条文章向量化失败，跳过：articleId={}", doc.getId());
                    continue;
                }
                // 调用 updateVectorOperation 构造「只写 vec_dense」的局部更新操作
                operations.add(updateVectorOperation(index, doc.getId(), SearchFieldConstant.FIELD_VEC_DENSE, vector));
            }
            // 调用批量更新：只写向量字段（其余字段保持原值），任一条失败即抛出中止重建
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
     * 重建切片索引向量：分页扫描切片 → 按 chunk_text 原文向量化 → bulk 局部更新 chunk_vector。
     *
     * @return 成功更新条数；Embedding 提供方未就绪时返回 0（整体跳过，属降级而非失败）
     */
    @Override
    public int rebuildChunkVectors() {
        /*
         * 业务逻辑步骤：
         * 1. 可用性检查：提供方未就绪则整体跳过（与文章重建同一口径）；
         * 2. search_after 循环分页扫描切片（按主键升序推进游标）；
         * 3. 逐条以 chunk_text 原文为输入向量化（与事件链路同一口径），单条失败只跳过；
         * 4. 每页把成功向量组成本批 bulk 局部更新（只写 chunk_vector）；
         * 5. 页不满说明已到末尾，循环结束后同步索引 _meta。
         */

        // 提供方未就绪：整体跳过（与文章重建同一可用性口径）
        if (!vectorService.available()) {
            log.warn("[向量重建] Embedding 提供方未就绪，跳过 article_chunks 重建");
            return 0;
        }
        // 变量：切片索引名（与事件链路写入同一索引）
        String index = properties.getIndex().getChunk();
        // 调用 scanBatchSize 取扫描批量（配置非法时兜底 1，保证分页可推进）
        int batch = scanBatchSize();
        // 变量：累计成功更新条数（方法返回值）
        int updated = 0;
        // 变量：search_after 游标（null 表示第一页；此后每页取上一页最后一条的排序值续扫）
        List<FieldValue> cursor = null;


        // 步骤2：循环扫描直到空页/不满页（每轮处理一页；页内失败仅影响单条，不中断整轮）
        while (true) {
            // 调用 scan 按游标扫描一页（失败抛异常中止重建，运维动作不静默）
            SearchResponse<ArticleChunkDoc> response = scan(index, batch, cursor, ArticleChunkDoc.class);
            // 变量：本页命中列表
            List<Hit<ArticleChunkDoc>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                break;
            }
            // 变量：本页待执行的 bulk 操作集合（逐条构造，页末统一提交）
            List<BulkOperation> operations = new ArrayList<>();
            for (Hit<ArticleChunkDoc> hit : hits) {
                // 变量：本条切片文档源数据
                ArticleChunkDoc doc = hit.source();
                // 脏数据（缺切片主键/正文）跳过
                if (doc == null || !StringUtils.hasText(doc.getChunkId())
                        || !StringUtils.hasText(doc.getChunkText())) {
                    continue;
                }
                // 调用向量化：输入为 chunk_text 原文（与事件链路写入口径一致）
                List<Float> vector = vectorService.embed(doc.getChunkText());
                // 单条失败跳过，后续可重跑补齐
                if (vector == null) {
                    log.warn("[向量重建] 单条切片向量化失败，跳过：chunkId={}", doc.getChunkId());
                    continue;
                }
                // 调用 updateVectorOperation 构造「只写 chunk_vector」的局部更新操作
                operations.add(updateVectorOperation(index, doc.getChunkId(),
                        SearchFieldConstant.FIELD_CHUNK_VECTOR, vector));
            }
            // 调用批量更新并累计成功数（任一条失败即抛出中止重建）
            updated += bulkUpdate(operations, "article_chunks");
            // 不足一页说明已到末尾
            if (hits.size() < batch) {
                break;
            }
            // 下一页游标：最后一条的排序值（主键）
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
     * 重建跑完后，把当前使用的 Embedding 模型名和 向量维度 写到 ES 索引的 _meta 里，
     * 方便运维确认“这个索引里的向量是什么版本的”。
     *
     * 向量维度 = 一段文本被模型压缩成向量后，这个数组的长度。
     *向量维度就是"模型把一段文本变成了多长的数字数组"，它是向量检索的硬约束——写错了就报错，换了就得全量重建。
     *
     * <p>只写 meta 不动字段 mapping（`_meta` 为只读元信息，putMapping 幂等）；
     * 失败仅告警：向量数据已重建成功，meta 登记失败不阻断运维流程，可稍后重跑一次重建刷新。</p>
     */
    private void updateEmbeddingMeta() {
        /*
         * 业务逻辑步骤：
         * 1. 校验配置：模型名未配置则跳过（防把空值/占位值登记为真实模型）；
         * 2. 遍历文章与切片两个索引，逐个 putMapping 更新 _meta（模型名 + 维度）；
         * 3. 单索引失败仅告警——向量数据已重建成功，meta 登记失败可稍后重跑刷新。
         */
        // 变量：配置中的 Embedding 模型名（登记进 _meta 的真实值）
        String model = properties.getEmbedding().getModel();
        // 模型未配置时不写 meta，避免把空值/占位值误登记为真实模型
        if (!StringUtils.hasText(model)) {
            log.warn("[向量重建] embedding.model 未配置，跳过索引 _meta 更新");
            return;
        }
        // 变量：配置中的向量维度（须与 mapping 声明一致）
        int dimension = properties.getEmbedding().getDimension();
        // 步骤2：遍历两索引逐个登记（单索引失败仅告警，不影响另一个）
        for (String index : List.of(properties.getIndex().getArticle(), properties.getIndex().getChunk())) {
            try {
                // 只更新 _meta 元信息：模型名 + 维度（JsonData 包装，保持索引元信息类型一致）
                Map<String, JsonData> meta = Map.of(
                        SearchFieldConstant.META_EMBEDDING_MODEL, JsonData.of(model),
                        SearchFieldConstant.META_EMBEDDING_DIMS, JsonData.of(dimension));
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
     * 它是向量全量重建时的「分页扫描器」——用 search_after 按主键升序一页一页地翻 ES，
     * 只扫公开文档，规避深分页限制，给后面的向量化 + bulk 更新提供稳定的数据输入。
     *
     * @param index    索引名
     * @param batch    每批条数
     * @param cursor   上一批最后一条的排序值（首批为 null）
     * @param docClass 文档映射类型
     * @param <T>      文档映射泛型
     * @return 本页命中
     * @throws IllegalStateException ES 扫描异常（直接抛出——重建属运维动作，失败必须显式暴露）
     */
    private <T> SearchResponse<T> scan(String index, int batch, List<FieldValue> cursor, Class<T> docClass) {
        /*
         * 业务逻辑步骤：
         * 1. 构造主键升序排序——既是稳定排序，也是 search_after 的游标基准；
         * 2. 构造请求：公开闸门 term 过滤 + 每页条数 + 上一页游标（首批不带）；
         * 3. 执行查询返回本页命中；失败直接抛出，不能静默半途而废。
         */
        try {
            // 按主键升序排序，作为 search_after 游标（keyword 字段可直接排序）
            SortOptions idAsc = SortOptions.of(s -> s.field(f -> f
                    .field(SearchFieldConstant.FIELD_ID)
                    .order(SortOrder.Asc)));
            // 变量：搜索请求构建器（索引 + 页大小 + 公开闸门 + 游标排序）
            SearchRequest.Builder builder = new SearchRequest.Builder()
                    .index(index)
                    .size(batch)
                    // 仅公开文档参与重建（与写入侧 PUBLIC 闸门一致）
                    .query(termFilter(SearchFieldConstant.FIELD_VISIBILITY, SearchGateConstant.VISIBILITY_PUBLIC))
                    .sort(List.of(idAsc));
            // 非首页才带 search_after 游标（首批从头扫）
            if (cursor != null && !cursor.isEmpty()) {
                builder.searchAfter(cursor);
            }
            // 调用 search 执行本页扫描
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
        /*
         * 业务逻辑步骤：
         * 1. 构造 UpdateAction：doc 只含单个向量字段（Map.of 单键），其余字段保持原值；
         * 2. 把 action 挂到 bulk update 操作上（index + id 定位目标文档）。
         */
        // update + doc：局部更新，避免整篇替换导致其他字段丢失
        // 注意：doc 位于 UpdateAction.Builder（UpdateOperation.Builder 只承载 index/id），故先构造 action 再挂载
        UpdateAction<Object, Object> action = UpdateAction.of(builder -> builder
                .doc(Map.of(field, vector)));
        // 调用 BulkOperation 包装为 update 操作：index + id 定位文档，挂载上面构造的 action
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
     * @throws IllegalStateException 任一条目失败或 ES 整体异常（重建结果必须完整，调用方重跑即可补齐）
     */
    private int bulkUpdate(List<BulkOperation> operations, String context) {
        /*
         * 业务逻辑步骤：
         * 1. 空批次直接返回（尾页可能没有可更新文档）；
         * 2. 执行 _bulk 并逐条统计成功/失败（失败条目 error 留痕，便于定位主键/向量字段问题）；
         * 3. 任一条失败即抛出——重建要求完整，调用方重跑即可补齐；ES 异常包装为统一异常。
         */
        // 空批次直接返回（尾页可能没有可更新文档）
        if (operations.isEmpty()) {
            return 0;
        }
        try {
            // 调用 _bulk 执行本批全部操作
            BulkResponse response = elasticsearchClient.bulk(b -> b.operations(operations));
            // 变量：本批成功条数（返回值）
            int success = 0;
            // 步骤2：逐条校验结果（error 为空即成功）
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
        // 调用 Math.max 兜底：配置为 0/负数时按 1 处理，保证分页循环可推进
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
        // 调用 TermQuery 构造精确匹配，再转成通用 Query 类型
        return TermQuery.of(t -> t.field(field).value(FieldValue.of(value)))._toQuery();
    }
}
