package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.litisle.litislesearch.domain.entity.ArticleChunkDoc;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.service.ArticleChunkService;
import com.litisle.litislesearch.service.VectorService;
import com.litisle.litislesearch.utils.SearchTextUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RAG 知识库切片服务实现（批次二）。
 *
 * 处理流程（rebuild）：
 * ① 正文长度不足门槛 → 只删旧块不写新块（文章变短后旧切片必须失效，防引用过期内容）；
 * ② 按 ingest.chunk-size / chunk-overlap 切片；
 * ③ 逐片组装 ArticleChunkDoc（chunk_id = articleId_序号）；
 * ④ 批量写入（_bulk），任一条失败抛 SEARCH_ES_UNAVAILABLE 由消费端延迟重试。
 *
 * 说明：向量 chunk_vector 由 VectorService 提供，当前模型未接入返回 null 时字段缺省
 * （批次七接入后需按 architecture §八-8 全量重建切片索引）。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleChunkServiceImpl implements ArticleChunkService {

    /** ES 客户端（索引读写唯一入口） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（切片索引名 + 切片粒度参数） */
    private final SearchProperties properties;

    /** 向量化服务（当前未接入模型，返回 null 时切片向量字段缺省） */
    private final VectorService vectorService;

    /**
     * 重建文章切片：先删旧块，再按当前正文写入新块。
     *
     * @param doc       文章索引文档（冗余字段来源）
     * @param plainText 正文纯文本
     */
    @Override
    public void rebuild(ArticleIndexDoc doc, String plainText) {
        // 步骤1：先删旧块（正文变短/标签变更时保证不残留过期切片）
        deleteByArticleId(Long.valueOf(doc.getId()));
        // 步骤2：正文长度门槛校验（过短文章不入 RAG 知识库）
        int minLength = properties.getIngest().getMinContentLength();
        if (plainText == null || plainText.length() < minLength) {
            log.debug("[切片摄入] 正文过短不入库：articleId={}, length={}, min={}",
                    doc.getId(), plainText == null ? 0 : plainText.length(), minLength);
            return;
        }
        // 步骤3：切片（重叠切片防断章取义）
        List<String> chunks = SearchTextUtils.splitIntoChunks(plainText,
                properties.getIngest().getChunkSize(), properties.getIngest().getChunkOverlap());
        if (chunks.isEmpty()) {
            return;
        }
        // 步骤4：逐片组装文档（切片时间统一取当前，便于排查重建批次）
        LocalDateTime now = LocalDateTime.now();
        List<ArticleChunkDoc> chunkDocs = java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> buildChunkDoc(doc, chunks.get(index), index, now))
                .toList();
        // 步骤5：批量写入并校验结果（失败抛异常交给消费端重试，不吞错）
        bulkIndex(chunkDocs);
        log.info("[切片摄入] 完成：articleId={}, chunks={}", doc.getId(), chunkDocs.size());
    }

    /**
     * 删除文章全部切片（索引不存在视为成功，幂等）。
     *
     * @param articleId 文章 ID
     */
    @Override
    public void deleteByArticleId(Long articleId) {
        String chunkIndex = properties.getIndex().getChunk();
        try {
            // 索引未创建（首次部署）时直接跳过，避免 deleteByQuery 抛 index_not_found
            boolean exists = elasticsearchClient.indices().exists(e -> e.index(chunkIndex)).value();
            if (!exists) {
                return;
            }
            // 按 article_id 删除该文章所有切片（覆盖删除/转私密/驳回/封禁连带四条下架路径）
            elasticsearchClient.deleteByQuery(d -> d
                    .index(chunkIndex)
                    .query(q -> q.term(t -> t
                            .field(SearchBizConstant.FIELD_ARTICLE_ID)
                            .value(articleId))));
            log.info("[切片摄入] 删除旧切片：articleId={}", articleId);
        } catch (Exception e) {
            // 删除失败必须上抛：下架内容残留切片会被 RAG 继续引用（隐私红线）；
            // 用非 BizException 抛出，保证 MqAckHelper 走「延迟重试」而非「业务失败丢弃」
            throw new IllegalStateException("删除文章切片失败（ES 异常，稍后重试），articleId=" + articleId, e);
        }
    }

    /**
     * 组装单个切片文档。
     *
     * @param doc       文章索引文档
     * @param chunkText 切片正文
     * @param index     切片序号（从 0 开始）
     * @param now       切片入库时间
     * @return 切片文档
     */
    private ArticleChunkDoc buildChunkDoc(ArticleIndexDoc doc, String chunkText, int index, LocalDateTime now) {
        ArticleChunkDoc chunkDoc = new ArticleChunkDoc();
        // 切片主键：articleId_序号，保证同文章内唯一
        chunkDoc.setChunkId(doc.getId() + SearchBizConstant.CHUNK_ID_SEPARATOR + index);
        chunkDoc.setArticleId(Long.valueOf(doc.getId()));
        chunkDoc.setAuthorId(doc.getAuthorId());
        chunkDoc.setTitle(doc.getTitle());
        chunkDoc.setAuthorName(doc.getAuthorName());
        chunkDoc.setChunkText(chunkText);
        chunkDoc.setZone(doc.getZone());
        chunkDoc.setVisibility(SearchBizConstant.VISIBILITY_PUBLIC);
        // 发布时间取文章更新时间（content 无独立发布时间列）
        chunkDoc.setPublishTime(doc.getUpdateTime());
        chunkDoc.setIngestTime(now);
        // 向量字段：模型接入前为 null（@JsonInclude NON_NULL 序列化时自动省略）
        chunkDoc.setChunkVector(vectorService.embed(chunkText));
        return chunkDoc;
    }

    /**
     * 批量写入切片文档，任一条失败即抛异常（保证重试后数据完整）。
     *
     * @param chunkDocs 切片文档列表
     */
    private void bulkIndex(List<ArticleChunkDoc> chunkDocs) {
        String chunkIndex = properties.getIndex().getChunk();
        try {
            // 构建 _bulk 请求：逐条 index（id 为 chunkId，重复写入即覆盖）
            BulkRequest.Builder builder = new BulkRequest.Builder();
            for (ArticleChunkDoc chunkDoc : chunkDocs) {
                builder.operations(op -> op.index(idx -> idx
                        .index(chunkIndex)
                        .id(chunkDoc.getChunkId())
                        .document(chunkDoc)));
            }
            BulkResponse response = elasticsearchClient.bulk(builder.build());
            // 校验逐条结果：失败详情记日志并抛异常，交由消费端延迟重试
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        log.error("[切片摄入] 批量写入失败：chunkId={}, reason={}",
                                item.id(), item.error().reason());
                    }
                }
                throw new IllegalStateException("文章切片批量写入部分失败，articleId=" + chunkDocs.get(0).getArticleId());
            }
        } catch (Exception e) {
            // ES 不可用/网络异常统一包装为非 BizException（触发消费端延迟重试，不当业务失败丢弃）
            throw new IllegalStateException("文章切片批量写入失败（ES 异常，稍后重试）", e);
        }
    }
}
