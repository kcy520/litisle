package com.litisle.litisleai.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.litisle.litisleai.config.AiRagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * article_chunks 向量索引客户端（板块四 4.1：AI 自建自管索引的唯一 ES 读写出口）
 *
 * <p>【职责边界】本类只封装 ES 原生操作（建索引/批量写/按篇删/knn 检索），
 * 门控回查与引用语义在 RagIngestService/RagSearchService。</p>
 *
 * <p>【失败语义】ES 异常直接上抛 IOException/RuntimeException：摄入侧转 MQ 消费重试，
 * 检索侧转降级无引用回答，不在本层吞错。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkIndexClient {

    /** ES 客户端（AiRagConfig 装配，rag.enabled=true 时 Bean 必在） */
    private final ElasticsearchClient elasticsearchClient;

    /** RAG 配置（索引名/维度/检索参数来源） */
    private final AiRagProperties ragProperties;

    /**
     * 确保索引存在（不存在则按 mapping 创建：contentVector 为 dense_vector）
     *
     * @throws IOException ES 访问失败上抛
     */
    public void ensureIndex() throws IOException {
        String index = ragProperties.getEsIndex();
        boolean exists = elasticsearchClient.indices().exists(e -> e.index(index)).value();
        if (exists) {
            return;
        }
        // mapping：contentVector 用 dense_vector（维度取配置）；其余常用字段 keyword/text/date
        Map<String, Property> propertiesMap = new HashMap<>();
        propertiesMap.put("chunkId", Property.of(p -> p.keyword(k -> k)));
        propertiesMap.put("articleId", Property.of(p -> p.keyword(k -> k.index(true))));
        propertiesMap.put("chunkIndex", Property.of(p -> p.keyword(k -> k.index(false))));
        propertiesMap.put("title", Property.of(p -> p.text(t -> t)));
        propertiesMap.put("authorId", Property.of(p -> p.keyword(k -> k)));
        propertiesMap.put("chunkText", Property.of(p -> p.text(t -> t)));
        // dense_vector：similarity 用枚举常量（String 重载不存在），cosine 相似度支撑 knn score
        propertiesMap.put("contentVector", Property.of(p -> p.denseVector(d ->
                d.dims(ragProperties.getVectorDimension()).index(true).similarity(DenseVectorSimilarity.Cosine))));
        propertiesMap.put("status", Property.of(p -> p.keyword(k -> k)));
        propertiesMap.put("visibility", Property.of(p -> p.keyword(k -> k)));
        propertiesMap.put("contentHash", Property.of(p -> p.keyword(k -> k)));
        propertiesMap.put("ingestTime", Property.of(p -> p.date(t -> t)));
        elasticsearchClient.indices().create(c -> c.index(index)
                .mappings(m -> m.properties(propertiesMap)));
        log.info("RAG 切片索引创建完成 index={} dims={}", index, ragProperties.getVectorDimension());
    }

    /**
     * 批量覆盖写入切片（幂等：chunkId 为 _id，重复写入即覆盖）
     *
     * @param docs 切片文档列表
     * @throws IOException ES 访问失败上抛
     */
    public void bulkUpsert(List<ArticleChunkDoc> docs) throws IOException {
        if (docs.isEmpty()) {
            return;
        }
        String index = ragProperties.getEsIndex();
        BulkRequest.Builder builder = new BulkRequest.Builder();
        for (ArticleChunkDoc doc : docs) {
            builder.operations(op -> op.index(idx -> idx.index(index).id(doc.getChunkId()).document(doc)));
        }
        var response = elasticsearchClient.bulk(builder.build());
        // 部分失败逐条告警（失败项由消费重试整篇重摄入兜底，chunkId 覆盖写保证幂等）
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                log.error("切片写入失败 chunkId={} reason={}", item.id(), item.error().reason());
            }
        }
        if (response.errors()) {
            throw new IllegalStateException("RAG 切片批量写入存在失败项，转消费重试 articleId=" + docs.get(0).getArticleId());
        }
        log.info("切片批量写入完成 index={} count={}", index, docs.size());
    }

    /**
     * 按文章删除全部切片（下架/删文/转私密同步移除，隐私红线；幂等：无命中即空操作）
     *
     * @param articleId 文章 ID
     * @throws IOException ES 访问失败上抛
     */
    public void deleteByArticle(Long articleId) throws IOException {
        String index = ragProperties.getEsIndex();
        var response = elasticsearchClient.deleteByQuery(d -> d
                .index(index)
                .query(Query.of(q -> q.term(t -> t.field("articleId").value(String.valueOf(articleId))))));
        log.info("按篇删除切片完成 articleId={} deleted={}", articleId, response.deleted());
    }

    /**
     * knn 向量检索（TopK + 相似度阈值过滤在调用方执行；knn score=cosine 相似度）
     *
     * @param queryVector 查询向量
     * @param topK        召回条数
     * @return 命中的切片文档列表（含 _score）
     * @throws IOException ES 访问失败上抛
     */
    public List<ChunkHit> knnSearch(List<Float> queryVector, int topK) throws IOException {
        String index = ragProperties.getEsIndex();
        SearchRequest request = SearchRequest.of(s -> s
                .index(index)
                .knn(k -> k
                        .field("contentVector")
                        .queryVector(queryVector)
                        .k(topK)
                        .numCandidates(Math.max(topK * 10, 50))));
        SearchResponse<ArticleChunkDoc> response = elasticsearchClient.search(request, ArticleChunkDoc.class);
        return response.hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> new ChunkHit(hit.source(), hit.score() == null ? 0.0D : hit.score()))
                .toList();
    }

    /**
     * 检索命中载体（切片文档 + 相似度得分）
     */
    public record ChunkHit(ArticleChunkDoc doc, double score) {
    }
}
