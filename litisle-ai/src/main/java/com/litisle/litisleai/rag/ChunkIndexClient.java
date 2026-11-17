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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>【通俗说明】本类是程序和 Elasticsearch 之间的"传话筒"：所有对 article_chunks 向量
 * 索引的读写都从这里过，上层不直接碰 ES。它负责四件事——① 建索引（第一次用之前把
 * "表"建好）；② 批量写入（把切好的段落连同向量存进去，重复写自动覆盖）；③ 按篇删除
 * （文章下架/删除时，把它的所有切片一次清干净）；④ 相似度检索（拿一个问题向量去找
 * 库里最像的 K 个段落）。注意它的职责边界：只管"存和取"，业务判断（权限门控、引用
 * 是否过期等）都在上层服务做。出错从不吞掉，直接往上抛，由调用方决定重试还是降级。</p>
 *
 * <p>【条件装配】litisle.ai.rag.enabled=false 时本 Bean 缺席（依赖的 ElasticsearchClient
 * 同为条件装配 Bean，硬注入在开关关闭时会因缺 Bean 阻断启动，故一并条件化）。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "litisle.ai.rag", name = "enabled", havingValue = "true")
public class ChunkIndexClient {

    /** ES 客户端（AiRagConfig 装配，与本 Bean 同受 rag.enabled 开关控制，开关开启时必在） */
    private final ElasticsearchClient elasticsearchClient;

    /** RAG 配置（索引名/维度/检索参数来源） */
    private final AiRagProperties ragProperties;

    /**
     * 确保索引存在（不存在则按 mapping 创建：contentVector 为 dense_vector）
     *
     * @throws IOException ES 访问失败上抛
     *
     * <p>【通俗说明】做一件事：先问 ES "这个索引建过了吗"，建过就直接返回；
     * 没建过就按字段类型表（mapping）创建——重点是把 contentVector 声明成向量类型，
     * 维度从配置读（bge-m3 是 1024 维），相似度算法用 cosine（余弦相似度），
     * 这样后续的"以向量搜相似段落"才能跑起来。</p>
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
     *
     * <p>【通俗说明】把一批切片"打包快递"一次发给 ES 写入，每片用 chunkId 当主键，
     * 重复写直接覆盖旧数据（所以叫幂等覆盖写）。发完后逐条核对快递签收结果：
     * 哪片失败就打日志点名，只要有一片失败就抛异常——宁可让上游重试整篇重传，
     * 也不能让数据悄悄丢了一半没人知道。</p>
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
     *
     * <p>【通俗说明】按文章 ID 把这篇文章的所有切片一次删干净——文章下架、删除或
     * 转私密的硬性要求，不能让用户已经看不到的内容还留在 AI 知识库里被检索到。
     * 删不到（本来就没有）也不报错，天然幂等，重试安全。</p>
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
     *
     * <p>【通俗说明】向量检索的核心："拿问题找相似段落"。把查询向量交给 ES 的 knn
     * 接口，它会先粗筛一大票候选（候选数取 topK 的 10 倍、至少 50 个，防止漏掉
     * 好结果），再精排出最像的 K 个返回。这里的过滤是全库范围的；相似度阈值和
     * TopK 截断由调用方自己再筛，本方法只负责"召回"。</p>
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
     * 单文范围内 knn 向量检索（板块九 1.4 读后追问：articleId term filter 收窄召回范围，
     * 只在目标文章自身切片中检索，不混用全库检索）
     *
     * @param queryVector 查询向量
     * @param articleId   目标文章 ID（filter articleId）
     * @param topK        召回条数
     * @return 该篇命中切片列表（含 _score）；无命中返回空列表
     * @throws IOException ES 访问失败上抛
     *
     * <p>【通俗说明】和 knnSearch 做的事一样，但多加了一个 articleId 过滤器：
     * 只在这篇文章自己的切片里找，绝不混进其他文章的内容。适用场景是
     * "读后追问"——用户正读着某篇文章提问，答案只需要从这一篇里找。</p>
     */
    public List<ChunkHit> knnSearchByArticle(List<Float> queryVector, Long articleId, int topK) throws IOException {
        String index = ragProperties.getEsIndex();
        SearchRequest request = SearchRequest.of(s -> s
                .index(index)
                .knn(k -> k
                        .field("contentVector")
                        .queryVector(queryVector)
                        .k(topK)
                        .numCandidates(Math.max(topK * 10, 50))
                        // 单文 filter：knn 召回范围收窄到目标文章自身切片
                        .filter(f -> f.term(t -> t.field("articleId").value(String.valueOf(articleId))))));
        SearchResponse<ArticleChunkDoc> response = elasticsearchClient.search(request, ArticleChunkDoc.class);
        return response.hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> new ChunkHit(hit.source(), hit.score() == null ? 0.0D : hit.score()))
                .toList();
    }

    /**
     * 按序取单文全部切片（板块九 1.5 大纲导航：chunkIndex 升序 = 原文段落顺序）
     *
     * @param articleId 目标文章 ID
     * @return 该篇全部切片（chunkIndex 升序）；无切片返回空列表
     * @throws IOException ES 访问失败上抛
     *
     * <p>【通俗说明】把某篇文章的全部切片按 chunkIndex（切片序号）从小到大取出来，
     * 还原成原文的段落顺序，用于"大纲导航"这类需要通读全文的场景。实现上有个
     * 小妥协：chunkIndex 在 ES 里没建排序索引，排不了序，所以先把数据取回来
     * 在内存里排——单篇文章切片量有限（一次最多取 500 条），这样做没有压力。</p>
     */
    public List<ArticleChunkDoc> listByArticle(Long articleId) throws IOException {
        String index = ragProperties.getEsIndex();
        SearchRequest request = SearchRequest.of(s -> s
                .index(index)
                .query(q -> q.term(t -> t.field("articleId").value(String.valueOf(articleId))))
                // chunkIndex 为 keyword 类型（index=false 不可排序），按 chunkId 语义排序不可行——
                // 内存按 chunkIndex 排序（单篇切片量级有限，召回上限内取全量）
                .size(500));
        SearchResponse<ArticleChunkDoc> response = elasticsearchClient.search(request, ArticleChunkDoc.class);
        return response.hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> hit.source())
                .sorted(java.util.Comparator.comparing(
                        doc -> doc.getChunkIndex() == null ? Integer.MAX_VALUE : doc.getChunkIndex()))
                .toList();
    }

    /**
     * 检索命中载体（切片文档 + 相似度得分）
     *
     * <p>【通俗说明】检索结果的一条：命中的那段切片内容 + 它与查询的相似度得分，
     * 得分越高说明越相似（0 分兜底表示 ES 没给分）。</p>
     */
    public record ChunkHit(ArticleChunkDoc doc, double score) {
    }
}
