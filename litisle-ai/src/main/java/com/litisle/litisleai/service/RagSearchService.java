package com.litisle.litisleai.service;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.config.AiRagProperties;
import com.litisle.litisleai.provider.embedding.EmbeddingProvider;
import com.litisle.litisleai.rag.ArticleChunkDoc;
import com.litisle.litisleai.rag.ChunkIndexClient;
import com.litisle.litisleai.rag.RagContext;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.feign.ContentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    /** 内容 bizType：1-文章 */
    private static final int BIZ_TYPE_ARTICLE = 1;

    /** 发布态：2-已发布（引用可见性回查口径，与摄入门控一致） */
    private static final int STATUS_PUBLISHED = 2;

    /** 可见性：1-公开 */
    private static final int VISIBILITY_PUBLIC = 1;

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
     * 切片有效性回查：可见性（下架即失效）+ 版本比对（contentHash 不一致标注已更新）
     *
     * @param doc 检索命中的切片
     * @return 校验结果（removed=引用移除；outdated=内容已更新标注）
     */
    private ChunkVerification verifyChunk(ArticleChunkDoc doc) {
        try {
            Result<ContentDTO> resp = contentClient.getContent(BIZ_TYPE_ARTICLE, doc.getArticleId());
            ContentDTO content = (resp == null || !resp.isSuccess()) ? null : resp.getData();
            // 回查失败/文章已不存在：按「无法确认」处理——引用移除（防引用失效内容冒充现行内容）
            if (content == null) {
                log.warn("引用回查失败，引用移除 articleId={}", doc.getArticleId());
                return new ChunkVerification(true, false);
            }
            // 可见性回查：非已发布/非公开 → 引用移除（下架即失效，隐私红线）
            if (content.getStatus() == null || content.getStatus() != STATUS_PUBLISHED
                    || content.getVisibility() == null || content.getVisibility() != VISIBILITY_PUBLIC) {
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
}
