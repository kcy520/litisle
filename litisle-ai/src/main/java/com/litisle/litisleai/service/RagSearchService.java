package com.litisle.litisleai.service;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.config.AiRagProperties;
import com.litisle.litisleai.constant.AiContentConstant;
import com.litisle.litisleai.provider.embedding.EmbeddingProvider;
import com.litisle.litisleai.rag.ArticleChunkDoc;
import com.litisle.litisleai.rag.ChunkIndexClient;
import com.litisle.litisleai.rag.RagContext;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dto.content.ArticleIndexDocDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.feign.ContentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * RAG 检索与引用回查服务（板块四 4.2：Embedding → TopK + 阈值过滤 → 引用可见性/版本回查 → prompt 片段）
 *
 * <p>【引用铁律（Q75）】引用必须可溯源：检索命中后回查 content 权威态——
 * ① 非 status=2/visibility=1 → 移除引用（下架即失效）；② contentHash 与切片记录不一致
 * → 标注「内容已更新」（改稿只更新元数据不重建切片的兜底口径）。</p>
 *
 * <p>【降级铁律（Q54）】RAG 不可用（通道/ES/回查失败）降级为无引用回答并显著标注
 * 「未引用站内检索结果」——本类失败时返回 null，由调用方（知识库检索工具）返回占位说明，
 * 涉及事实性结论只答「暂无相关资料」，不混入模型自身知识冒充站内回答。</p>
 *
 * <p>【引用回传】命中的有效引用写入 RagContext（终答组装时 drain 进响应 citations 字段）。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSearchService {

    /** 向量通道（查询向量化） */
    private final EmbeddingProvider embeddingProvider;

    /** 切片索引客户端（knn 检索） */
    private final ChunkIndexClient chunkIndexClient;

    /** 内容服务客户端（引用可见性/版本回查） */
    private final ContentClient contentClient;

    /** RAG 配置（topK/minScore） */
    private final AiRagProperties ragProperties;

    /**
     * 检索并组装 prompt 知识片段（供知识库检索工具回喂模型）
     *
     * @param query 用户查询
     * @return prompt 片段文本（含引用溯源信息）；RAG 不可用或无有效命中返回 null（调用方降级）
     */
    public String searchForPrompt(String query) {
        // ① 查询向量化（失败捕获转降级，不中断会话）
        List<Float> queryVector;
        try {
            queryVector = embeddingProvider.embed(query);
        } catch (Exception e) {
            log.warn("RAG 检索降级：查询向量化失败 queryChars={}", query == null ? 0 : query.length(), e);
            return null;
        }
        // ② knn TopK 召回 + 相似度阈值过滤
        List<ChunkIndexClient.ChunkHit> hits;
        try {
            hits = chunkIndexClient.knnSearch(queryVector, ragProperties.getTopK());
        } catch (Exception e) {
            log.warn("RAG 检索降级：ES 检索失败", e);
            return null;
        }
        List<ChunkIndexClient.ChunkHit> qualified = hits.stream()
                .filter(hit -> hit.score() >= ragProperties.getMinScore())
                .toList();
        if (qualified.isEmpty()) {
            log.info("RAG 检索无有效命中（阈值 {} 以上为空）topK={}", ragProperties.getMinScore(), ragProperties.getTopK());
            return null;
        }
        // ③ 引用回查（可见性 + 版本比对 Q75）+ 组装 prompt 片段 + 记录引用卡片
        List<String> passages = new ArrayList<>();
        int validCount = 0;
        for (ChunkIndexClient.ChunkHit hit : qualified) {
            ArticleChunkDoc doc = hit.doc();
            ChunkVerification verification = verifyChunk(doc);
            if (verification.removed()) {
                // 下架即失效：引用移除（卡片仍记录 removed=true 供前端标注）
                RagContext.record(new RagContext.Citation(doc.getArticleId(), doc.getTitle(),
                        doc.getContentHash(), true));
                continue;
            }
            // 有效引用：prompt 片段带溯源元数据（模型可引用）；卡片记录版本一致性
            passages.add("【引用" + (++validCount) + "】文章《" + doc.getTitle() + "》（articleId="
                    + doc.getArticleId() + (verification.outdated() ? "，内容已更新" : "") + "）：\n" + doc.getChunkText());
            RagContext.record(new RagContext.Citation(doc.getArticleId(), doc.getTitle(),
                    doc.getContentHash(), false));
        }
        if (passages.isEmpty()) {
            log.info("RAG 检索命中全部被引用回查移除 topK={}", qualified.size());
            return null;
        }
        log.info("RAG 检索完成 hits={} valid={} scoreMin={}", qualified.size(), passages.size(), ragProperties.getMinScore());
        // ④ 组装知识片段（含使用规则：只基于引用回答，事实性结论无引用只答暂无资料）
        return String.join("\n\n", passages)
                + "\n\n【使用规则】以上为站内知识库检索片段：回答须基于片段内容并引用来源文章 ID；"
                + "片段不足以支撑事实性结论时，只回答「暂无相关资料」，禁止混入模型自身知识冒充站内内容。";
    }

    /**
     * 相似文章推荐检索（板块五 5.1：F8 纯检索形态，按篇聚合不切片输出）
     *
     * <p>【业务逻辑】查询向量化 → knn 切片召回（多召回防同篇多片挤占名额）→ 阈值过滤 +
     * 按篇去重（同篇取最高分）→ 排除自身 → 逐篇回查（与 verifyChunk 同口径：已发布 + 公开 +
     * contentHash 比对；回查经 getArticleIndexDoc 一次取齐状态/指纹/作者昵称）→ 推荐卡片列表。</p>
     *
     * <p>【可见性铁律】检索命中不代表可推荐：回查失败/非已发布/非公开即移除（下架即失效）；
     * contentHash 与摄入时不一致标注「内容已更新」。</p>
     *
     * <p>【失败语义】向量化/ES 检索/回查异常直接上抛，由调用方（SimilarRecommendService）
     * 捕获降级为空列表（F8 边界铁律：不报错不降级抛错）。</p>
     *
     * @param excludeArticleId 需排除的自身文章 ID（推荐结果不含被浏览文章本身）
     * @param queryText        向量化查询文本（标题+摘要或正文首块）
     * @return 相似文章命中列表（按相似度降序，最多 similar.topK 篇）
     * @throws Exception 向量化失败 / ES 检索失败（调用方降级）
     */
    public List<SimilarArticleHit> similarArticles(Long excludeArticleId, String queryText) throws Exception {
        // ① 查询向量化（失败上抛转调用方降级）
        List<Float> queryVector = embeddingProvider.embed(queryText);
        // ② knn 切片召回：按切片召回 3 倍于推荐篇数的量，按篇去重后仍够 topK 个候选
        List<ChunkIndexClient.ChunkHit> hits = chunkIndexClient.knnSearch(
                queryVector, ragProperties.getSimilar().getTopK() * 3);
        // ③ 阈值过滤 + 按篇去重（同篇多片取最高分，LinkedHashMap 保持得分插入序）+ 排除自身
        LinkedHashMap<Long, ChunkIndexClient.ChunkHit> bestPerArticle = new LinkedHashMap<>();
        for (ChunkIndexClient.ChunkHit hit : hits) {
            ArticleChunkDoc doc = hit.doc();
            if (hit.score() < ragProperties.getSimilar().getMinScore()
                    || doc.getArticleId() == null
                    || doc.getArticleId().equals(excludeArticleId)) {
                continue;
            }
            bestPerArticle.merge(doc.getArticleId(), hit,
                    (existing, incoming) -> incoming.score() > existing.score() ? incoming : existing);
        }
        if (bestPerArticle.isEmpty()) {
            log.info("相似推荐无有效命中 minScore={} excludeArticleId={}",
                    ragProperties.getSimilar().getMinScore(), excludeArticleId);
            return List.of();
        }
        // ④ 取前 topK 篇候选，逐篇回查（同 verifyChunk 口径），回查通过组装推荐卡片
        List<SimilarArticleHit> result = new ArrayList<>();
        int candidateLimit = Math.min(bestPerArticle.size(), ragProperties.getSimilar().getTopK());
        for (ChunkIndexClient.ChunkHit hit : bestPerArticle.values().stream().toList().subList(0, candidateLimit)) {
            ArticleChunkDoc doc = hit.doc();
            SimilarArticleHit card = verifyForSimilar(doc, hit.score());
            if (card != null) {
                result.add(card);
            }
        }
        log.info("相似推荐检索完成 excludeArticleId={} candidates={} recommended={}",
                excludeArticleId, candidateLimit, result.size());
        return result;
    }

    /**
     * 相似推荐逐篇回查（与 verifyChunk 同口径，回查数据源换为 getArticleIndexDoc 一次取齐作者昵称）
     *
     * @param doc   命中的切片文档（承载摄入时 contentHash）
     * @param score 切片相似度得分
     * @return 推荐卡片；回查失败/非已发布/非公开返回 null（移除）
     */
    private SimilarArticleHit verifyForSimilar(ArticleChunkDoc doc, double score) {
        try {
            Result<ArticleIndexDocDTO> resp = contentClient.getArticleIndexDoc(doc.getArticleId());
            ArticleIndexDocDTO article = (resp == null || !resp.isSuccess()) ? null : resp.getData();
            // 回查失败/文章已不存在：按「无法确认」处理——移除（防推荐失效内容）
            if (article == null) {
                log.warn("相似推荐回查失败，候选移除 articleId={}", doc.getArticleId());
                return null;
            }
            // 可见性回查：非已发布/非公开 → 移除（下架即失效，隐私红线）
            if (article.getStatus() == null || article.getStatus() != AiContentConstant.STATUS_PUBLISHED
                    || article.getVisibility() == null || article.getVisibility() != AiContentConstant.VISIBILITY_PUBLIC) {
                log.info("相似推荐候选已不可见，移除 articleId={}", doc.getArticleId());
                return null;
            }
            // 版本比对（Q75 同口径）：摄入时 contentHash 与当前不一致 → 标注「内容已更新」
            boolean outdated = doc.getContentHash() != null && article.getContentHash() != null
                    && !doc.getContentHash().equals(article.getContentHash());
            return new SimilarArticleHit(doc.getArticleId(), article.getTitle(), article.getAuthorId(),
                    article.getAuthorName(), score, outdated);
        } catch (Exception e) {
            log.warn("相似推荐回查异常，候选移除 articleId={}", doc.getArticleId(), e);
            return null;
        }
    }

    /**
     * 切片有效性回查：可见性（下架即失效）+ 版本比对（contentHash 不一致标注已更新）
     *
     * @param doc 检索命中的切片
     * @return 校验结果（removed=引用移除；outdated=内容已更新标注）
     */
    private ChunkVerification verifyChunk(ArticleChunkDoc doc) {
        try {
            Result<ContentDTO> resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, doc.getArticleId());
            ContentDTO content = (resp == null || !resp.isSuccess()) ? null : resp.getData();
            // 回查失败/文章已不存在：按「无法确认」处理——引用移除（防引用失效内容冒充现行内容）
            if (content == null) {
                log.warn("引用回查失败，引用移除 articleId={}", doc.getArticleId());
                return new ChunkVerification(true, false);
            }
            // 可见性回查：非已发布/非公开 → 引用移除（下架即失效，隐私红线）
            if (content.getStatus() == null || content.getStatus() != AiContentConstant.STATUS_PUBLISHED
                    || content.getVisibility() == null || content.getVisibility() != AiContentConstant.VISIBILITY_PUBLIC) {
                log.info("引用内容已不可见，引用移除 articleId={}", doc.getArticleId());
                return new ChunkVerification(true, false);
            }
            // 版本比对（Q75）：contentHash 与切片记录不一致 → 标注「内容已更新」（移除或标注二选一，取标注）
            boolean outdated = doc.getContentHash() != null && content.getContentHash() != null
                    && !doc.getContentHash().equals(content.getContentHash());
            return new ChunkVerification(false, outdated);
        } catch (Exception e) {
            log.warn("引用回查异常，引用移除 articleId={}", doc.getArticleId(), e);
            return new ChunkVerification(true, false);
        }
    }

    /**
     * 切片校验结果载体
     */
    private record ChunkVerification(boolean removed, boolean outdated) {
    }

    /**
     * 相似文章命中载体（板块五 5.1 推荐卡片数据源）
     *
     * @param articleId  文章 ID
     * @param title      文章标题（content 当前值）
     * @param authorId   作者 ID
     * @param authorName 作者昵称（用户服务不可用可为空，仅展示用）
     * @param similarity 相似度得分（knn cosine score，取该篇最高分切片）
     * @param outdated   内容已更新标注（摄入时 contentHash 与当前不一致）
     */
    public record SimilarArticleHit(Long articleId, String title, Long authorId,
                                    String authorName, double similarity, boolean outdated) {
    }
}
