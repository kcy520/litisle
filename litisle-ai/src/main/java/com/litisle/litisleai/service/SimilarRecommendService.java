package com.litisle.litisleai.service;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.config.AiRagProperties;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.dto.SimilarArticleCard;
import com.litisle.litisleai.dto.XiaoyueSimilarRequest;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.provider.text.TextChatRequest;
import com.litisle.litisleai.provider.text.TextChatResult;
import com.litisle.litisleai.provider.text.TextModelProvider;
import com.litisle.litisleai.service.support.AiUsageDetail;
import com.litisle.litisleai.service.support.SuggestionValidator;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.feign.ContentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 相似文章推荐编排服务（板块五 5.1/5.2：F8 相似文章推荐，复用板块四 RAG 底座）
 *
 * <p>【职责边界】本类收口推荐链路编排：取稿 → 查询文本组装（标题+正文首块）→ 委托
 * RagSearchService.similarArticles 检索回查 → 计量落明细 → 5.2 可选理由生成（默认关闭）；
 * 检索与可见性回查在 RagSearchService，模型通道只服务理由生成。</p>
 *
 * <p>【边界铁律（F8）】只做相似检索，不输出对作者的负面评价性结论；RAG 缺席（rag.enabled=false）/
 * 取稿失败/检索失败/无命中 → 返回空列表明确响应，不报错不降级抛错；理由生成失败 → 静默返回
 * 无理由卡片（卡片列表不受影响）。</p>
 *
 * <p>【计量口径】纯检索形态 token=0 仍落一条明细保证可观测（modelVersion 取本地 embedding
 * 模型标识，零外部费用）；理由开启时对 TopN 候选按需生成（purpose=SIMILAR 走预算判定与计量，
 * 超限只跳过理由不停检索）；相似推荐为浏览伴随的只读能力，不做用户级配额拦截。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarRecommendService {

    /** 纯检索明细调用序号（每次推荐固定 1 条 token=0 明细） */
    private static final int RETRIEVAL_CALL_SEQ = 1;

    /** 理由生成调用序号（同一 requestId 下检索明细之后递增） */
    private static final int REASON_CALL_SEQ = 2;

    /** 查询文本正文首块长度（与切片 chunkSize 同口径，防超长正文全量向量化） */
    private static final int QUERY_BODY_MAX_CHARS = 500;

    /** RAG 检索服务（rag.enabled=false 时 Bean 缺席，ObjectProvider 取空 → 空列表响应） */
    private final ObjectProvider<RagSearchService> ragSearchProvider;

    /** 内容服务客户端（源文章回查，查询文本组装） */
    private final ContentClient contentClient;

    /** 文本模型通道（仅 5.2 理由生成使用；enabled=false 时调用抛明确异常转静默跳过） */
    private final TextModelProvider textModelProvider;

    /** 结构化校验器（5.2 理由输出校验） */
    private final SuggestionValidator validator;

    /** 计量明细服务 */
    private final AiUsageRecordService usageRecordService;

    /** 预算分账判定（仅理由生成前置判定） */
    private final AiBudgetService budgetService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** 通道配置（model 版本快照 / provider 标签） */
    private final AiProviderProperties providerProperties;

    /** Agent 配置（理由生成重试预算复用 structuredRetryBudget） */
    private final AiAgentProperties agentProperties;

    /** RAG 配置（similar 配置组：topK/minScore/理由开关） */
    private final AiRagProperties ragProperties;

    /**
     * 相似文章推荐主流程（POST /ai/xiaoyue/similar 编排入口）
     *
     * <p>【业务逻辑步骤】
     * 1. 参数校验（requestId/articleId 必传）；
     * 2. RAG 缺席检查（enabled=false → 空列表，F8 不报错）；
     * 3. 取源文章（标题+正文首块组装查询文本，取稿失败 → 空列表）；
     * 4. 检索回查（RagSearchService.similarArticles，异常降级空列表并落失败明细）；
     * 5. 纯检索计量（token=0 落一条明细保证可观测）；
     * 6. 5.2 可选增强：理由生成（默认关闭；预算超限/生成失败静默跳过）。</p>
     *
     * @param request 推荐请求（requestId/articleId 必传）
     * @param userId  当前登录用户 ID（controller 从 Sa-Token 会话取得；未登录为 null，匿名浏览计量归属为空）
     * @return 相似文章卡片列表（无命中/RAG 缺席/失败时为空列表，不报错）
     * @throws BizException 参数缺失（10001/80002）
     */
    public List<SimilarArticleCard> similar(XiaoyueSimilarRequest request, Long userId) {
        // ① 参数校验（requestId 是计量幂等键组成，必传）
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(com.litisle.commoncore.exception.ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        if (request.getArticleId() == null) {
            throw new BizException(com.litisle.commoncore.exception.ErrorCode.PARAM_ERROR, "articleId 必传");
        }
        // ② RAG 缺席：rag.enabled=false 时检索 Bean 整体缺席，按 F8 返回空列表明确响应
        RagSearchService ragSearchService = ragSearchProvider.getIfAvailable();
        if (ragSearchService == null) {
            log.info("RAG 未启用，相似推荐返回空列表 requestId={}", request.getRequestId());
            return List.of();
        }
        // ③ 取源文章组装查询文本（标题+正文首块；取稿失败按无法推荐处理，返回空列表）
        ContentDTO article = fetchArticle(request.getArticleId());
        if (article == null) {
            log.warn("相似推荐取稿失败，返回空列表 requestId={} articleId={}", request.getRequestId(), request.getArticleId());
            return List.of();
        }
        String queryText = buildQueryText(article);
        // ④ 检索回查（向量化/ES 异常降级空列表——F8 不报错；失败也落明细留痕）
        List<RagSearchService.SimilarArticleHit> hits;
        try {
            hits = ragSearchService.similarArticles(request.getArticleId(), queryText);
        } catch (Exception e) {
            log.warn("相似推荐检索降级，返回空列表 requestId={} articleId={}", request.getRequestId(), request.getArticleId(), e);
            recordRetrievalDetail(request, userId, false);
            return List.of();
        }
        // ⑤ 纯检索计量：token=0 仍落一条明细保证可观测（embedding 为本地模型零外部费用）
        recordRetrievalDetail(request, userId, true);
        // ⑥ 组装推荐卡片（回查未通过的候选已在检索层移除）
        AiRagProperties.Similar similarConfig = ragProperties.getSimilar();
        List<SimilarArticleCard> cards = hits.stream()
                .map(h -> {
                    SimilarArticleCard card = new SimilarArticleCard();
                    card.setArticleId(h.articleId());
                    card.setTitle(h.title());
                    card.setAuthorId(h.authorId());
                    card.setAuthorName(h.authorName());
                    card.setSimilarity(h.similarity());
                    card.setOutdated(h.outdated());
                    return card;
                })
                .toList();
        log.info("相似推荐完成 requestId={} articleId={} hits={}", request.getRequestId(), request.getArticleId(), cards.size());
        // ⑦ 5.2 可选增强：推荐理由生成（默认关闭；失败静默返回无理由卡片，不影响检索结果）
        if (similarConfig.isReasonEnabled() && !cards.isEmpty()) {
            cards = fillReasons(request, cards, similarConfig, userId,
                    article.getTitle() == null ? "" : article.getTitle());
        }
        return cards;
    }

    /**
     * 回查源文章（标题与正文用于组装查询文本；失败返回 null 由调用方返回空列表）
     *
     * @param articleId 文章 ID
     * @return 内容 DTO；不存在/服务异常返回 null
     */
    private ContentDTO fetchArticle(Long articleId) {
        try {
            Result<ContentDTO> resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, articleId);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                return null;
            }
            return resp.getData();
        } catch (Exception e) {
            // 日志纪律：只记 ID 不打正文
            log.warn("相似推荐取稿异常 articleId={}", articleId, e);
            return null;
        }
    }

    /**
     * 组装向量化查询文本：标题 + 正文首块（无摘要字段的 ContentDTO 以正文首块兜底）
     *
     * @param article 源文章
     * @return 查询文本（正文首块截取至 chunkSize 同口径长度）
     */
    private String buildQueryText(ContentDTO article) {
        String body = article.getBody() == null ? "" : article.getBody();
        // 首块截断：与切片 chunkSize 同口径，防超长正文全量向量化拖慢检索
        String firstBlock = body.length() > QUERY_BODY_MAX_CHARS ? body.substring(0, QUERY_BODY_MAX_CHARS) : body;
        return (article.getTitle() == null ? "" : article.getTitle()) + "\n" + firstBlock;
    }

    /**
     * 5.2 推荐理由生成：TopN 候选一句话理由（结构化输出 + 校验回喂重试，失败静默跳过）
     *
     * <p>【预算口径】purpose=SIMILAR 走预算判定；EXCEEDED 只跳过理由生成（检索卡片照常返回），
     * 不抛错不静默放行——理由是增强能力非主结果。</p>
     *
     * @param request       推荐请求（计量归属）
     * @param cards         检索得到的卡片列表（按相似度降序）
     * @param similarConfig 相似推荐配置
     * @param userId        当前登录用户 ID（计量归属；未登录为 null）
     * @param sourceTitle   源文章标题（主流程已取稿，理由 prompt 用）
     * @return 卡片列表（前 TopN 张尽量带上 reason，生成失败保持 null）
     */
    private List<SimilarArticleCard> fillReasons(XiaoyueSimilarRequest request, List<SimilarArticleCard> cards,
                                                 AiRagProperties.Similar similarConfig, Long userId, String sourceTitle) {
        // 预算判定：超限只跳过理由（可观测：记配额指标），检索结果不受影响
        if (budgetService.check(AiPurposeEnum.SIMILAR) == AiBudgetDecision.EXCEEDED) {
            log.warn("相似推荐理由生成预算超限，跳过理由生成 requestId={}", request.getRequestId());
            metrics.recordQuotaExceeded(AiPurposeEnum.SIMILAR.getCode());
            return cards;
        }
        int topN = Math.min(similarConfig.getReasonTopN(), cards.size());
        // 组装候选清单（序号 1 起，对应卡片列表前 TopN 位）
        StringBuilder candidateLines = new StringBuilder();
        for (int i = 0; i < topN; i++) {
            candidateLines.append(i + 1).append(".《").append(cards.get(i).getTitle()).append("》\n");
        }
        String originalTask = String.format(AiPromptConstants.SIMILAR_REASON_PROMPT,
                sourceTitle, candidateLines, similarConfig.getReasonMaxChars());
        // 自校验回喂循环（重试预算与结构化管道同源：1 次首轮 + structuredRetryBudget 次回喂）
        int totalAttempts = 1 + agentProperties.getStructuredRetryBudget();
        String lastError = null;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            long start = System.currentTimeMillis();
            // 首轮用原始任务 prompt；回喂轮用校验错误反馈模板（最小反思闭环）
            String userPrompt = attempt == 1 ? originalTask : String.format(
                    AiPromptConstants.STRUCTURED_RETRY_FEEDBACK, lastError, originalTask);
            try {
                TextChatResult result = textModelProvider.chat(TextChatRequest.builder()
                        .systemPrompt(AiPromptConstants.XIAOYUE_SYSTEM_PROMPT)
                        .userPrompt(userPrompt)
                        .temperature(0.0D)
                        .build());
                recordReasonSuccess(request, userId, result);
                // service 侧校验（不依赖模型自觉）：序号范围 + 一句话字数
                List<SuggestionValidator.ReasonItem> reasons = validator.validateReasons(
                        result.getContent(), topN, similarConfig.getReasonMaxChars());
                metrics.recordGeneration(normalizeScene(request.getScene()), AiMetrics.RESULT_SUCCESS);
                // 按序号回填理由（模型只给部分候选时，缺失候选保持 null）
                Map<Integer, String> reasonMap = new HashMap<>();
                for (SuggestionValidator.ReasonItem item : reasons) {
                    reasonMap.put(item.getIndex(), item.getReason());
                }
                List<SimilarArticleCard> filled = new java.util.ArrayList<>(cards);
                for (int i = 0; i < topN; i++) {
                    filled.get(i).setReason(reasonMap.get(i + 1));
                }
                return filled;
            } catch (BizException e) {
                // 模型通道失败：失败也留明细；重试预算内再试，预算耗尽静默返回无理由卡片
                recordReasonFail(request, userId);
                metrics.recordGeneration(normalizeScene(request.getScene()), AiMetrics.RESULT_FAIL);
                lastError = "模型调用失败：" + e.getMessage();
            } catch (IllegalArgumentException e) {
                // 校验不过：模型调用本身成功（已计量），记录具体错误回喂重试
                log.info("推荐理由输出校验未通过 requestId={} attempt={} error={}",
                        request.getRequestId(), attempt, e.getMessage());
                metrics.recordGeneration(normalizeScene(request.getScene()), AiMetrics.RESULT_FAIL);
                lastError = e.getMessage();
            } finally {
                metrics.recordLatency(AiPurposeEnum.SIMILAR.getCode(), System.currentTimeMillis() - start);
            }
        }
        // 重试预算耗尽：理由生成静默放弃（F8 边界铁律：不报错），卡片列表原样返回
        log.warn("推荐理由生成终态失败（重试预算耗尽），返回无理由卡片 requestId={}", request.getRequestId());
        return cards;
    }

    /**
     * 纯检索计量留痕：token=0 明细一条（成功/失败均落，保证可观测）
     *
     * <p>modelVersion 取本地 embedding 模型标识——embedding 为本地模型不产生外部费用；
     * ai_pricing 未配置该版本时成本按 0 核算（建议补 0 单价配置行消除缺价告警）。</p>
     *
     * @param request 推荐请求
     * @param userId  当前登录用户 ID（计量归属；未登录为 null）
     * @param success 检索是否成功（失败 token 同为 0，status=FAIL 留痕）
     */
    private void recordRetrievalDetail(XiaoyueSimilarRequest request, Long userId, boolean success) {
        AiUsageDetail detail = AiUsageDetail.builder()
                .requestId(request.getRequestId())
                .callSeq(RETRIEVAL_CALL_SEQ)
                .userId(userId)
                .purpose(AiPurposeEnum.SIMILAR)
                .scene(request.getScene())
                .modelVersion(ragProperties.getOllamaModel())
                .tokenIn(0)
                .tokenOut(0)
                .degraded(0)
                .occurredAt(LocalDateTime.now())
                .build();
        if (success) {
            usageRecordService.recordSuccess(detail);
        } else {
            usageRecordService.recordFail(detail);
        }
    }

    /**
     * 理由生成成功计量留痕（token 取 provider 返回值）
     */
    private void recordReasonSuccess(XiaoyueSimilarRequest request, Long userId, TextChatResult result) {
        usageRecordService.recordSuccess(AiUsageDetail.builder()
                .requestId(request.getRequestId())
                .callSeq(REASON_CALL_SEQ)
                .userId(userId)
                .purpose(AiPurposeEnum.SIMILAR)
                .scene(request.getScene())
                .modelVersion(result.getModelVersion())
                .tokenIn(result.getTokenIn())
                .tokenOut(result.getTokenOut())
                .degraded(0)
                .occurredAt(LocalDateTime.now())
                .build());
        metrics.recordCall(AiPurposeEnum.SIMILAR.getCode(), providerProperties.getProviderName(),
                AiMetrics.RESULT_SUCCESS);
    }

    /**
     * 理由生成失败计量留痕（失败即无成本防护：token 按 0 落库）
     */
    private void recordReasonFail(XiaoyueSimilarRequest request, Long userId) {
        usageRecordService.recordFail(AiUsageDetail.builder()
                .requestId(request.getRequestId())
                .callSeq(REASON_CALL_SEQ)
                .userId(userId)
                .purpose(AiPurposeEnum.SIMILAR)
                .scene(request.getScene())
                .modelVersion(providerProperties.getModel())
                .tokenIn(null)
                .tokenOut(null)
                .degraded(0)
                .occurredAt(LocalDateTime.now())
                .build());
        metrics.recordCall(AiPurposeEnum.SIMILAR.getCode(), providerProperties.getProviderName(),
                AiMetrics.RESULT_FAIL);
    }

    /**
     * 场景标签归一化（null 兜底 default，防指标空标签）
     */
    private String normalizeScene(String scene) {
        return scene == null || scene.isBlank() ? AiMetrics.SCENE_DEFAULT : scene;
    }
}
