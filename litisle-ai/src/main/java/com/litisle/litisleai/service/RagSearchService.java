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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>【通俗说明】AI 问答的"查资料 + 核实出处"环节：先拿用户问题去向量库搜相似的文章
 * 切片（召回 TopK + 相似度门槛过滤），搜到后不直接用——每条命中都要回 content 服务
 * "验明正身"：文章是不是还处于已发布+公开状态（下架的立即剔除）、内容指纹和入库时
 * 是否一致（改过稿的标注"内容已更新"）。核实通过的片段组装成带出处的知识片段交给
 * 模型，同时登记成引用卡片供前端展示。任何环节失败（向量化挂了/ES 挂了/回查挂了）
 * 都返回 null，由调用方降级为"无引用回答"——宁可不引用，也不能拿模型自己的知识
 * 冒充站内资料。还提供单文内检索（读后追问用）和相似文章推荐（读文章时的侧栏推荐）。</p>
 *
 * <p>【条件装配】litisle.ai.rag.enabled=false 时本 Bean 缺席：调用方（XiaoyueTools /
 * SimilarRecommendService）经 ObjectProvider.getIfAvailable() 取空走降级分支；
 * 依赖的 EmbeddingProvider/ElasticsearchClient 同为条件装配 Bean，硬注入在开关关闭时
 * 会因缺 Bean 阻断启动，故一并条件化。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "litisle.ai.rag", name = "enabled", havingValue = "true")
public class RagSearchService {

    /** 向量通道（查询向量化；AiRagConfig 装配，与本 Bean 同受 rag.enabled 开关控制，开关开启时必在） */
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
     *
     * <p>【通俗说明】全库检索四步：① 问题向量化（失败转降级）；② 向量库搜 TopK 个
     * 相似切片并按相似度门槛过滤（门槛以下说明"不太像"，宁缺毋滥）；③ 每条命中回查
     * 原文状态（下架的剔除但仍记录失效卡片供前端标注），拼出带文章 ID 的引用片段；
     * ④ 附上"使用规则"——要求模型只基于片段回答、注明来源，没把握就说"暂无相关
     * 资料"，禁止拿自己的知识冒充站内内容。</p>
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
     * 单文切片语义检索（板块九 1.4 读后追问底座：按 articleId 过滤检索该篇切片，不混用全库检索）
     *
     * <p>【引用铁律（Q75）】与 searchForPrompt 同口径：verifyChunk 回查——非已发布/非公开/
     * 回查失败即剔除；contentHash 不一致标注「内容已更新」；命中引用写入 RagContext 供终答回传。</p>
     *
     * @param articleId 目标文章 ID（只在该篇切片范围内检索）
     * @param query     用户问题（向量化检索依据）
     * @return prompt 片段文本（含溯源与使用规则）；RAG 不可用/无有效命中返回 null（调用方降级）
     *
     * <p>【通俗说明】和 searchForPrompt 流程一样，但范围收窄到一篇文章自己的切片
     * （读后追问场景：用户正读着某篇文章提问）。使用规则也更严格——回答只能基于
     * 这一篇的片段，问题超出范围要明确引导用户换入口，不许跨篇混答。</p>
     */
    public String retrieveArticlePassages(Long articleId, String query) {
        // ① 查询向量化（失败捕获转降级，不中断会话）
        List<Float> queryVector;
        try {
            queryVector = embeddingProvider.embed(query);
        } catch (Exception e) {
            log.warn("单文切片检索降级：查询向量化失败 articleId={} queryChars={}",
                    articleId, query == null ? 0 : query.length(), e);
            return null;
        }
        // ② 单文 knn 召回（articleId filter 收窄范围）+ 阈值过滤
        List<ChunkIndexClient.ChunkHit> hits;
        try {
            hits = chunkIndexClient.knnSearchByArticle(queryVector, articleId, ragProperties.getTopK());
        } catch (Exception e) {
            log.warn("单文切片检索降级：ES 检索失败 articleId={}", articleId, e);
            return null;
        }
        List<ChunkIndexClient.ChunkHit> qualified = hits.stream()
                .filter(hit -> hit.score() >= ragProperties.getMinScore())
                .toList();
        if (qualified.isEmpty()) {
            log.info("单文切片检索无有效命中 articleId={} minScore={}", articleId, ragProperties.getMinScore());
            return null;
        }
        // ③ 引用回查（可见性 + 版本比对 Q75）+ 组装单文片段
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
            passages.add("【引用" + (++validCount) + "】《" + doc.getTitle() + "》（片段 "
                    + (doc.getChunkIndex() == null ? "?" : doc.getChunkIndex() + 1)
                    + (verification.outdated() ? "，内容已更新" : "") + "）：\n" + doc.getChunkText());
            RagContext.record(new RagContext.Citation(doc.getArticleId(), doc.getTitle(),
                    doc.getContentHash(), false));
        }
        if (passages.isEmpty()) {
            log.info("单文切片检索命中全部被引用回查移除 articleId={}", articleId);
            return null;
        }
        log.info("单文切片检索完成 articleId={} hits={} valid={}", articleId, qualified.size(), passages.size());
        // ④ 组装片段（单文口径：回答只基于该篇切片，超范围引导走全文问答入口）
        return String.join("\n\n", passages)
                + "\n\n【使用规则】以上为该篇文章自身切片：回答须仅基于片段内容并引用片段位置；"
                + "问题超出该篇文章范围时，明确引导用户走全文问答入口（不混用全库检索），"
                + "术语不在该篇范围内时明确说明，禁止编造。";
    }

    /**
     * 按序取单文全部有效切片（板块九 1.5 大纲导航 / 1.8 金句摘录底座：chunkIndex 升序 =
     * 原文段落顺序，逐片过可见性回查）
     *
     * @param articleId 目标文章 ID
     * @return 有效切片文本列表（chunkIndex 升序；文章不可见/无切片/回查全移除返回空列表）
     *
     * <p>【通俗说明】把一篇文章的全部切片按原文段落顺序取出来（大纲导航、金句摘录
     * 用），每片照样过一遍可见性回查——下架的内容一片都不能漏出去。</p>
     */
    public List<String> listArticleChunkTexts(Long articleId) {
        // 逐片回查口径与 verifyChunk 一致（非已发布/非公开/回查失败即剔除）
        List<ArticleChunkDoc> docs;
        try {
            docs = chunkIndexClient.listByArticle(articleId);
        } catch (Exception e) {
            log.warn("单文全切片获取失败 articleId={}", articleId, e);
            return List.of();
        }
        if (docs.isEmpty()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (ArticleChunkDoc doc : docs) {
            if (!verifyChunk(doc).removed()) {
                texts.add(doc.getChunkText());
            }
        }
        log.info("单文全切片获取完成 articleId={} total={} valid={}", articleId, docs.size(), texts.size());
        return texts;
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
     *
     * <p>【通俗说明】"找相似文章"四步：① 查询文本向量化；② 按切片多召回一些
     * （推荐篇数的 3 倍，防止同一篇文章的多个切片互相挤占名额）；③ 阈值过滤 +
     * 按篇去重（同篇多个切片只留得分最高的那个）+ 排除被浏览的文章本身；④ 取前
     * TopK 篇逐篇回查核实（状态、可见性、指纹），通过的组装成推荐卡片。和问答检索
     * 不同：这里输出的是"整篇文章"卡片，不是切片片段。</p>
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
     *
     * <p>【通俗说明】推荐候选的"验明正身"：回查文章当前状态——查不到、已下架、
     * 转私密都直接淘汰（宁可少推荐，不能推荐用户看不见的东西）；改过稿的照常推荐
     * 但打上"内容已更新"标记。</p>
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
     *
     * <p>【通俗说明】引用片段的"验明正身"：查不到原文、已下架、转私密 → 判定失效
     * （removed，引用必须剔除）；内容指纹对不上 → 判定改过稿（outdated，引用保留
     * 但标注"内容已更新"）。回查失败按"无法确认"从严处理——也剔除。</p>
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
     *
     * <p>【通俗说明】两项结论：removed（引用要不要剔除）+ outdated（要不要标注
     * "内容已更新"）。</p>
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
     *
     * <p>【通俗说明】一张推荐卡片的数据：文章 ID/标题/作者/相似度得分 + 改稿标记。</p>
     */
    public record SimilarArticleHit(Long articleId, String title, Long authorId,
                                    String authorName, double similarity, boolean outdated) {
    }
}
