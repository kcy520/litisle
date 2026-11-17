package com.litisle.litisleai.service;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.config.AiRagProperties;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.dto.DuplicateCheckRequest;
import com.litisle.litisleai.dto.SimilarArticleCard;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.provider.text.TextChatRequest;
import com.litisle.litisleai.provider.text.TextChatResult;
import com.litisle.litisleai.provider.text.TextModelProvider;
import com.litisle.litisleai.service.support.AiUsageDetail;
import com.litisle.litisleai.vo.DuplicateCheckResponse;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import com.litisle.litisleapi.dto.search.SimilarConditionRequestDTO;
import com.litisle.litisleapi.feign.SearchClient;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布前查重服务（板块十二 4.2：创作侧发布前调用相似检索换调用时机——复用板块八
 * 条件版语义检索底座，相似度超外置阈值提示重复风险；0~1 次 LLM：检索零生成，
 * 仅 explain=true 且命中时 1 次 LLM 生成「哪里相似」说明）
 *
 * <p>【职责边界】本类只做查重编排（检索 + 阈值判定 + 可选说明生成 + 计量），不做
 * 入口建设（创作页触发入口归 content 侧）；与 SimilarRecommendService 平行，共用
 * SearchClient 条件版检索契约与 SIMILAR 桶计量口径。</p>
 *
 * <p>【计量口径】检索 token=0 落明细（SIMILAR 桶 scene=duplicate-check callSeq=1）；
 * explain=true 且命中时说明生成 1 次（SIMILAR 桶 scene=duplicate-explain callSeq=2）；
 * 预算 EXCEEDED 直接拒绝（查重是创作向增值能力，不降级放行）。</p>
 *
 * <p>【失败语义】检索失败/预算超限抛 BizException（前端明确提示）；说明生成失败不
 * 中断查重主结果（risk/articles 已就绪，explanation 置空并标注失败原因）。</p>
 *
 * <p>【通俗说明】作者点"发布"前，先用 AI 检查"这篇会不会和站里已有的文章太像"：
 * 把待发文章的标题+截断正文拿去条件版语义检索（只比主题核心，不是全文指纹比对），
 * 相似度超过配置阈值就判定"有重复风险"并列出相似文章。用户可以勾选"解释一下"，
 * AI 会额外用一句话说明哪里相似——但这个解释只是锦上添花，生成失败不影响查重
 * 结论。查重失败（检索挂了/预算超了）会明确报错，绝不静默放行发布。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateCheckService {

    /** 查重检索计量场景（计量明细留痕） */
    private static final String SCENE_DUPLICATE_CHECK = "duplicate-check";

    /** 查重说明生成计量场景 */
    private static final String SCENE_DUPLICATE_EXPLAIN = "duplicate-explain";

    /** 查重检索召回条数（只取 top 命中做风险判定与展示，防无界召回） */
    private static final int DUPLICATE_CHECK_TOP_K = 10;

    /** 说明生成用正文截断字符数（说明只需主题级上下文，不放大 token） */
    private static final int EXPLAIN_BODY_MAX_CHARS = 2000;

    /** 查重检索计量模型版本标识（检索 token=0 明细口径，与相似推荐 recordRetrievalDetail 同源） */
    private static final String RETRIEVAL_MODEL_VERSION = "es-conditional-similar";

    /** 搜索服务客户端（条件版语义检索：queryText 语义 knn + 公开铁律由 search 强制） */
    private final SearchClient searchClient;

    /** RAG 配置（查重风险阈值外置来源） */
    private final AiRagProperties ragProperties;

    /** provider 配置（计量 modelVersion/providerName 来源） */
    private final AiProviderProperties providerProperties;

    /** 文本模型通道（explain=true 时「哪里相似」说明生成） */
    private final TextModelProvider textModelProvider;

    /** 计量明细服务（SIMILAR 桶明细落库） */
    private final AiUsageRecordService usageRecordService;

    /** 预算服务（SIMILAR 桶判定） */
    private final AiBudgetService budgetService;

    /** 可观测指标（ai_call_total 计数） */
    private final AiMetrics metrics;

    /**
     * 发布前查重主流程（板块十二 4.2）
     *
     * <p>【业务逻辑步骤】
     * 1. 预算判定（SIMILAR 桶 EXCEEDED 拒绝，不降级放行）；
     * 2. 组装查重 queryText（标题 + 截断正文）经条件版语义检索召回候选；
     * 3. 相似度 ≥ 外置阈值判定 risk，命中卡片组装返回；
     * 4. explain=true 且命中时 1 次 LLM 生成「哪里相似」说明（失败降级置空不中断主结果）。</p>
     *
     * @param requestId 计量幂等键
     * @param userId    发起用户 ID（匿名创作页场景可为空，计量按匿名口径落明细）
     * @param request   查重请求（title/body 必传，explain 可空）
     * @return 查重响应（risk/threshold/articles + 可选 explanation）
     * @throws BizException 预算超限 / 检索失败（调用方明确提示，不静默放行）
     *
     * <p>【通俗说明】四步：① 查预算（超限直接拒绝）；② 标题+截断正文做语义检索，
     * 召回前 10 个候选（检索失败记账后上抛，绝不静默放行）；③ 相似度 ≥ 阈值的
     * 判为风险并列出命中卡片；④ 用户要求解释且确实命中时，让模型生成"哪里相似"
     * 的一句话说明（失败置空不影响主结论）。</p>
     */
    public DuplicateCheckResponse check(String requestId, Long userId, DuplicateCheckRequest request) {
        // ① 预算判定：SIMILAR 桶超限直接拒绝（查重是创作向增值能力，不降级放行）
        if (budgetService.check(AiPurposeEnum.SIMILAR) == AiBudgetDecision.EXCEEDED) {
            log.warn("发布前查重预算超限 requestId={}", requestId);
            metrics.recordQuotaExceeded(AiPurposeEnum.SIMILAR.getCode());
            throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED);
        }
        // ② 条件版语义检索召回（公开铁律由 search 侧强制；无命中不报错返回空列表）
        List<SimilarArticleVO> hits;
        try {
            SimilarConditionRequestDTO dto = new SimilarConditionRequestDTO();
            dto.setRequestId(requestId);
            // 标题 + 截断正文作为查重主旨：只比对主题与内容核心，非全文指纹比对
            dto.setQueryText(buildQueryText(request.getTitle(), request.getBody()));
            dto.setTopK(DUPLICATE_CHECK_TOP_K);
            Result<List<SimilarArticleVO>> searchResp = searchClient.similarByCondition(dto);
            hits = searchResp == null || !searchResp.isSuccess() || searchResp.getData() == null
                    ? List.of() : searchResp.getData();
        } catch (Exception e) {
            // 检索失败计量留痕（FAIL 明细）后上抛：查重失败必须明确提示，不静默放行发布
            recordRetrievalDetail(requestId, userId, false);
            throw e;
        }
        recordRetrievalDetail(requestId, userId, true);
        // ③ 阈值判定（相似度 ≥ 外置阈值即风险）+ 命中卡片组装
        double threshold = ragProperties.getSimilar().getDuplicateScoreThreshold();
        List<SimilarArticleCard> articles = new ArrayList<>();
        boolean risk = false;
        for (SimilarArticleVO hit : hits) {
            // score 为原始 double（search 侧 fuseBySortHint 已做 null 兜底 0.0）
            double score = hit.getScore();
            if (score < threshold) {
                continue;
            }
            risk = true;
            SimilarArticleCard card = new SimilarArticleCard();
            card.setArticleId(hit.getArticleId());
            card.setTitle(hit.getTitle());
            card.setAuthorId(hit.getAuthorId());
            card.setAuthorName(hit.getAuthorName());
            card.setSimilarity(score);
            articles.add(card);
        }
        log.info("发布前查重完成 requestId={} hits={} aboveThreshold={} risk={}",
                requestId, hits.size(), articles.size(), risk);
        // ④ 可选说明生成（explain=true 且命中时 1 次 LLM；失败不中断主结果）
        String explanation = null;
        if (risk && Boolean.TRUE.equals(request.getExplain())) {
            explanation = generateExplanation(requestId, userId, request, articles);
        }
        return DuplicateCheckResponse.builder()
                .risk(risk)
                .threshold(threshold)
                .explanation(explanation)
                .articles(articles)
                .build();
    }

    /**
     * 生成「哪里相似」说明（1 次 LLM 固定管道，不走 Agent；失败降级置空并告警）
     *
     * @param requestId 计量幂等键
     * @param userId    发起用户 ID
     * @param request   查重请求
     * @param articles  阈值命中的卡片列表（说明生成的库内相似文章上下文）
     * @return 说明文本；生成失败返回 null（主结果不中断）
     *
     * <p>【通俗说明】把待发文章（标题+截断正文）和命中的相似文章清单交给模型，
     * 要它一句话说清"像在哪"；成功失败都记账，失败返回 null 不影响查重主结果。</p>
     */
    private String generateExplanation(String requestId, Long userId, DuplicateCheckRequest request,
                                       List<SimilarArticleCard> articles) {
        try {
            // 双段 prompt：system 固定口径 + user 查重说明任务（正文截断防 token 放大）
            TextChatResult result = textModelProvider.chat(TextChatRequest.builder()
                    .systemPrompt(AiPromptConstants.XIAOYUE_SYSTEM_PROMPT)
                    .userPrompt(String.format(AiPromptConstants.DUPLICATE_EXPLAIN_PROMPT,
                            request.getTitle(), truncate(request.getBody()),
                            buildHitsSummary(articles)))
                    .build());
            // 说明生成计量留痕（callSeq=2：检索 1 之后）
            usageRecordService.recordSuccess(AiUsageDetail.builder()
                    .requestId(requestId)
                    .callSeq(2)
                    .userId(userId)
                    .purpose(AiPurposeEnum.SIMILAR)
                    .scene(SCENE_DUPLICATE_EXPLAIN)
                    .modelVersion(result.getModelVersion())
                    .tokenIn(result.getTokenIn())
                    .tokenOut(result.getTokenOut())
                    .degraded(0)
                    .occurredAt(LocalDateTime.now())
                    .build());
            metrics.recordCall(AiPurposeEnum.SIMILAR.getCode(), providerProperties.getProviderName(), "success");
            return result.getContent();
        } catch (Exception e) {
            // 说明生成失败：主查重结果（risk/articles）已就绪，降级置空不中断
            log.warn("查重说明生成失败（主结果不受影响）requestId={}", requestId, e);
            usageRecordService.recordFail(AiUsageDetail.builder()
                    .requestId(requestId)
                    .callSeq(2)
                    .userId(userId)
                    .purpose(AiPurposeEnum.SIMILAR)
                    .scene(SCENE_DUPLICATE_EXPLAIN)
                    .modelVersion(providerProperties.getModel())
                    .tokenIn(null)
                    .tokenOut(null)
                    .degraded(0)
                    .occurredAt(LocalDateTime.now())
                    .build());
            metrics.recordCall(AiPurposeEnum.SIMILAR.getCode(), providerProperties.getProviderName(), "fail");
            return null;
        }
    }

    /**
     * 组装查重 queryText（标题 + 截断正文）
     *
     * @param title 待发布标题
     * @param body  待发布正文
     * @return 查重主旨文本（标题优先，正文按说明生成同口径截断）
     *
     * <p>【通俗说明】拼查重文本：标题换行接截断后的正文。</p>
     */
    private String buildQueryText(String title, String body) {
        return title + "\n" + truncate(body);
    }

    /**
     * 正文截断（EXPLAIN_BODY_MAX_CHARS 口径）
     *
     * @param body 原文正文
     * @return 截断后正文（原长不超上限时原样返回）
     *
     * <p>【通俗说明】正文超过 2000 字就砍到 2000，防止 token 成本放大。</p>
     */
    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > EXPLAIN_BODY_MAX_CHARS ? body.substring(0, EXPLAIN_BODY_MAX_CHARS) : body;
    }

    /**
     * 组装命中文章摘要（说明生成的库内相似文章上下文；逐行「标题（相似度）」）
     *
     * @param articles 阈值命中的卡片列表
     * @return 逐行「标题（相似度）」文本
     *
     * <p>【通俗说明】把命中文章整理成"《标题》（相似度 0.85）"的逐行清单，
     * 作为模型解释"哪里相似"时的参考资料。</p>
     */
    private String buildHitsSummary(List<SimilarArticleCard> articles) {
        StringBuilder summary = new StringBuilder();
        for (SimilarArticleCard card : articles) {
            summary.append("- 《").append(card.getTitle()).append("》（相似度 ")
                    .append(card.getSimilarity()).append("）\n");
        }
        return summary.toString().trim();
    }

    /**
     * 查重检索计量留痕（token=0 明细，SIMILAR 桶 scene=duplicate-check callSeq=1）
     *
     * @param requestId 计量幂等键
     * @param userId    发起用户 ID
     * @param success   检索是否成功
     *
     * <p>【通俗说明】检索本身不花钱（token=0），但落一条明细保证每次查重可追溯。</p>
     */
    private void recordRetrievalDetail(String requestId, Long userId, boolean success) {
        AiUsageDetail detail = AiUsageDetail.builder()
                .requestId(requestId)
                .callSeq(1)
                .userId(userId)
                .purpose(AiPurposeEnum.SIMILAR)
                .scene(SCENE_DUPLICATE_CHECK)
                .modelVersion(RETRIEVAL_MODEL_VERSION)
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
}
