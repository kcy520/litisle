package com.litisle.litisleai.service;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.agent.AgentCallContext;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.constant.AiContentConstant;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.dto.XiaoyueChatRequest;
import com.litisle.litisleai.dto.XiaoyueSuggestRequest;
import com.litisle.litisleai.dto.XiaoyueSuggestionResponse;
import com.litisle.litisleai.dto.DraftRequest;
import com.litisle.litisleai.dto.RewriteRequest;
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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 小悦直连管道服务（板块二 2.5：固定管道摘要/标签建议 + 2.3 的降级通道实现）
 *
 * <p>【职责边界】本类承载「固定管道直连通道」：单次 provider 调用 + service 侧结构化
 * 自校验回喂（2.4，重试预算固定 1 次），不经 Agent 循环；调用序号复用调用方传入的
 * AgentCallContext（降级链路 callSeq 连续累计，直连入口自建上下文从 1 起）。
 * 建议态一律不落库（D4），成功建议由调用方决定是否写建议缓存。</p>
 *
 * <p>【失败语义】模型通道失败/取稿失败/自校验重试耗尽 → 返回降级响应（degraded=true，
 * message=「暂时无法生成，可手动填写」），不落库；直连入口的预算超限/配额超限仍以
 * 明确错误码上抛（80003/80004），降级链路预算已在入口侧判定过不再重复拦截。</p>
 *
 * <p>【计量承诺】每次 provider 调用成功/失败均按 requestId+callSeq 落明细；
 * 降级链路明细 degraded=1（降级期产生），直连入口明细 degraded=0。</p>
 *
 * <p>【通俗说明】小悦 AI 的"备用生产线"：一套不走 Agent、固定步骤的生成流程
 * （拼固定 prompt → 调一次模型 → 校验 → 不合格回喂重试一次）。它身兼两职：
 * ① 独立的直连入口（摘要/标签/润色/结构分析四个端点直接走这里，简单任务
 * 不必劳驾 Agent）；② 所有 Agent 链路的统一降级通道（Agent 挂了都退到这里）。
 * 无论成功失败都照常记账，降级产生的账单会特别标注 degraded=1；最终失败就
 * 返回"可手动填写"的提示，不落库不报错。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XiaoyueSuggestionService {

    /** 降级提示文案（D3/D13：可手动填写，不落库） */
    private static final String DEGRADED_MESSAGE = "AI 暂时无法生成该内容，请稍后重试或手动填写";

    /** 默认 AI 生成提示文案（D3/D13 展示标识） */
    private static final String AI_NOTICE_MESSAGE = "AI 生成内容，仅供参考，请确认后再保存";

    /** 文本模型通道（固定管道单次调用） */
    private final TextModelProvider textModelProvider;

    /** 结构化建议校验器（2.4 校验侧） */
    private final SuggestionValidator validator;

    /** 建议态缓存（P7：命中直接回查，不重复计量） */
    private final SuggestionCacheService cacheService;

    /** 计量明细服务 */
    private final AiUsageRecordService usageRecordService;

    /** 预算分账判定（直连入口前置判定） */
    private final AiBudgetService budgetService;

    /** 用户级配额校验（直连入口前置判定，板块三统一接线） */
    private final AiUserQuotaService userQuotaService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** 通道配置（model 版本快照 / provider 标签） */
    private final AiProviderProperties providerProperties;

    /** Agent 配置（重试预算） */
    private final AiAgentProperties agentProperties;

    /** 内容服务客户端（回查稿件） */
    private final ContentClient contentClient;

    /**
     * 直连入口：摘要建议（POST /ai/xiaoyue/summary 固定管道）
     *
     * @param request 直连请求（requestId/articleId 必传）
     * @param userId  当前登录用户 ID（controller 从 Sa-Token 会话取得；用户级配额与计量归属）
     * @return 摘要建议（degraded=true 时为失败提示）
     * @throws BizException 参数缺失（10001/80002）、预算超限（80004）
     *
     * <p>【通俗说明】摘要建议直连入口：走统一前置检查后执行摘要固定管道。</p>
     */
    public XiaoyueSuggestionResponse summaryDirect(XiaoyueSuggestRequest request, Long userId) {
        return directEntry(request.getRequestId(), userId, request.getScene(), AiPurposeEnum.SUMMARY,
                ctx -> runSummaryPipeline(ctx, request.getArticleId(), 0));
    }

    /**
     * 直连入口：标签建议（POST /ai/xiaoyue/tag 固定管道）
     *
     * @param request 直连请求（requestId/articleId 必传）
     * @param userId  当前登录用户 ID（controller 从 Sa-Token 会话取得；用户级配额与计量归属）
     * @return 标签建议（degraded=true 时为失败提示）
     * @throws BizException 参数缺失（10001/80002）、预算超限（80004）
     *
     * <p>【通俗说明】标签建议直连入口：走统一前置检查后执行打标固定管道。</p>
     */
    public XiaoyueSuggestionResponse tagDirect(XiaoyueSuggestRequest request, Long userId) {
        return directEntry(request.getRequestId(), userId, request.getScene(), AiPurposeEnum.TAGGING,
                ctx -> runTagPipeline(ctx, request.getArticleId(), 0));
    }

    /**
     * 直连入口：润色建议（板块三 3.3：POST /ai/xiaoyue/polish 固定管道；一次性动作不建会话）
     *
     * @param request 对话请求（requestId 必传；articleId/message 至少一项）
     * @param userId  当前登录用户 ID（controller 从 Sa-Token 会话取得；用户级配额与计量归属）
     * @return 润色建议（degraded=true 时为失败提示）
     * @throws BizException 参数缺失（10001/80002）、预算超限（80004）
     *
     * <p>【通俗说明】润色建议直连入口：可关联稿件也可直接润色选中文本。</p>
     */
    public XiaoyueSuggestionResponse polishDirect(XiaoyueChatRequest request, Long userId) {
        validateChatRequestForDirect(request, true);
        return directEntry(request.getRequestId(), userId, request.getScene(), AiPurposeEnum.POLISH,
                ctx -> runPolishPipeline(ctx, request, 0));
    }

    /**
     * 直连入口：结构分析建议（板块三 3.3：POST /ai/xiaoyue/analyze-structure 固定管道）
     *
     * @param request 对话请求（requestId/articleId 必传——结构分析基于全文）
     * @param userId  当前登录用户 ID（controller 从 Sa-Token 会话取得；用户级配额与计量归属）
     * @return 结构分析建议（degraded=true 时为失败提示）
     * @throws BizException 参数缺失（10001/80002）、预算超限（80004）
     *
     * <p>【通俗说明】结构分析建议直连入口：必须关联稿件（基于全文分析）。</p>
     */
    public XiaoyueSuggestionResponse structureDirect(XiaoyueChatRequest request, Long userId) {
        validateChatRequestForDirect(request, false);
        return directEntry(request.getRequestId(), userId, request.getScene(), AiPurposeEnum.STRUCTURE,
                ctx -> runStructurePipeline(ctx, request, 0));
    }

    /**
     * 降级通道：摘要建议（2.3 Agent 循环异常时由 XiaoyueChatService 调用，ctx 已存在）
     *
     * @param request  原始对话请求
     * @param context  调用上下文（agentPhase 已解除，callSeq 连续累计）
     * @return 摘要建议（degraded=true 时为失败提示，不抛异常——降级铁律）
     *
     * <p>【通俗说明】Agent 挂掉后摘要的降级入口：复用调用方上下文继续计数。</p>
     */
    XiaoyueSuggestionResponse summaryFallback(XiaoyueChatRequest request, AgentCallContext context) {
        return runSummaryPipeline(context, request.getArticleId(), 1);
    }

    /**
     * 降级通道：标签建议（同 summaryFallback 口径）
     *
     * @param request 原始对话请求
     * @param context 调用上下文
     * @return 标签建议（degraded=true 时为失败提示）
     *
     * <p>【通俗说明】Agent 挂掉后标签建议的降级入口。</p>
     */
    XiaoyueSuggestionResponse tagFallback(XiaoyueChatRequest request, AgentCallContext context) {
        return runTagPipeline(context, request.getArticleId(), 1);
    }

    /**
     * 降级通道：问答直答（QA 固定管道：无检索直接回答，计量 purpose=QA）
     *
     * @param request 原始对话请求
     * @param context 调用上下文
     * @return 问答回答（degraded=true 时为失败提示）
     *
     * <p>【通俗说明】Agent 挂掉后问答的降级入口：不做知识库检索，直接拿问题问模型。</p>
     */
    XiaoyueSuggestionResponse qaFallback(XiaoyueChatRequest request, AgentCallContext context) {
        return runQaPipeline(context, request.getMessage(), 1);
    }

    /**
     * 降级通道：润色建议（板块三 3.3，Agent 循环异常时由 XiaoyueChatService 调用）
     *
     * @param request 原始对话请求
     * @param context 调用上下文（agentPhase 已解除，callSeq 连续累计）
     * @return 润色建议（degraded=true 时为失败提示，不抛异常——降级铁律）
     *
     * <p>【通俗说明】Agent 挂掉后润色建议的降级入口。</p>
     */
    XiaoyueSuggestionResponse polishFallback(XiaoyueChatRequest request, AgentCallContext context) {
        return runPolishPipeline(context, request, 1);
    }

    /**
     * 降级通道：结构分析建议（板块三 3.3，同 polishFallback 口径）
     *
     * @param request 原始对话请求
     * @param context 调用上下文
     * @return 结构分析建议（degraded=true 时为失败提示）
     *
     * <p>【通俗说明】Agent 挂掉后结构分析的降级入口。</p>
     */
    XiaoyueSuggestionResponse structureFallback(XiaoyueChatRequest request, AgentCallContext context) {
        return runStructurePipeline(context, request, 1);
    }

    /**
     * 降级通道：多级改写建议（板块十五 15.1，Agent 循环异常时由 WritingAssistService 调用；
     * request 已由编排服务归一化——原文定位回填、候选数 clamp、风格偏好空值兜底）
     *
     * @param request 归一化后的改写请求
     * @param context 调用上下文（agentPhase 已解除，callSeq 连续累计）
     * @return 改写建议（degraded=true 时为失败提示，不抛异常——降级铁律）
     *
     * <p>【通俗说明】Agent 挂掉后多级改写的降级入口（请求已被编排服务预先归一化）。</p>
     */
    XiaoyueSuggestionResponse rewriteFallback(RewriteRequest request, AgentCallContext context) {
        return runRewritePipeline(context, request, 1);
    }

    /**
     * 降级通道：文章草稿生成（板块十五 15.2，同 rewriteFallback 口径）
     *
     * @param request 草稿请求（theme 必传已在编排侧校验）
     * @param context 调用上下文
     * @return 草稿建议（degraded=true 时为失败提示）
     *
     * <p>【通俗说明】Agent 挂掉后文章草稿的降级入口。</p>
     */
    XiaoyueSuggestionResponse draftFallback(DraftRequest request, AgentCallContext context) {
        return runDraftPipeline(context, request, 1);
    }

    /**
     * 多级改写固定管道（板块十五 15.1 降级通道）：组 prompt → 单次调用（回喂重试 1 次）
     * → 按粒度校验 → 计量留痕
     *
     * <p>【通俗说明】用改写专用模板拼 prompt，走结构化通用骨架（含按粒度校验）。</p>
     */
    private XiaoyueSuggestionResponse runRewritePipeline(AgentCallContext context, RewriteRequest request, int degraded) {
        // 组装固定管道 prompt（候选数/风格偏好已由编排服务归一化，缺省值直接可用）
        String originalTask = String.format(AiPromptConstants.REWRITE_DIRECT_PROMPT,
                AiContentConstant.targetTypeLabel(request.getTargetType()),
                request.getCandidateCount(),
                agentProperties.getRewriteReasonMaxChars(),
                agentProperties.getRewriteTitleMaxChars(),
                request.getStylePreference(),
                request.getOriginalText());
        return runStructuredPipeline(context, AiPurposeEnum.POLISH, originalTask, degraded,
                raw -> validator.validateRewrite(raw, request.getTargetType(), request.getCandidateCount(),
                        agentProperties.getRewriteReasonMaxChars(), agentProperties.getRewriteTitleMaxChars(),
                        request.getOriginalText()),
                suggestion -> {
                    XiaoyueSuggestionResponse resp = baseResponse(context, AiPurposeEnum.POLISH, degraded);
                    resp.setTargetType(request.getTargetType());
                    resp.setRewriteCandidates(suggestion.getRewriteCandidates());
                    resp.setRewriteSuggestions(suggestion.getRewriteSuggestions());
                    return resp;
                });
    }

    /**
     * 文章草稿固定管道（板块十五 15.2 降级通道）：组 prompt → 单次调用（回喂重试 1 次）
     * → 字数上限校验 → 计量留痕
     *
     * <p>【通俗说明】用草稿专用模板拼 prompt，走结构化通用骨架（含字数上限校验）。</p>
     */
    private XiaoyueSuggestionResponse runDraftPipeline(AgentCallContext context, DraftRequest request, int degraded) {
        // 组装固定管道 prompt（主题必传已由编排服务校验；字数范围文本随请求组装）
        String originalTask = String.format(AiPromptConstants.DRAFT_DIRECT_PROMPT,
                agentProperties.getDraftMaxChars(),
                request.getTheme(),
                request.getOutline() == null || request.getOutline().isBlank() ? "无" : request.getOutline(),
                request.wordRangeText());
        return runStructuredPipeline(context, AiPurposeEnum.POLISH, originalTask, degraded,
                raw -> validator.validateDraft(raw, agentProperties.getDraftMaxChars()),
                suggestion -> {
                    XiaoyueSuggestionResponse resp = baseResponse(context, AiPurposeEnum.POLISH, degraded);
                    resp.setDraft(suggestion.getDraft());
                    resp.setOutline(suggestion.getOutline());
                    return resp;
                });
    }

    /**
     * 直连入口统一前置：参数校验 → 预算判定 → 缓存回查 → 开启上下文执行管道 → 成功写缓存
     *
     * @param requestId 请求幂等键
     * @param userId    用户ID
     * @param scene     业务场景
     * @param purpose   用途（SUMMARY/TAGGING/POLISH/STRUCTURE）
     * @param pipeline  管道执行体（返回建议响应）
     * @return 建议响应
     *
     * <p>【通俗说明】直连入口统一流水线：查用户配额 → 查平台预算 → 查建议缓存
     * （命中直接返回不重复花钱）→ 开上下文执行具体管道 → 成功写缓存。预算/配额
     * 超限抛明确错误码，其余失败转降级提示。</p>
     */
    private XiaoyueSuggestionResponse directEntry(String requestId, Long userId, String scene, AiPurposeEnum purpose,
                                                  Pipeline pipeline) {
        // ① 用户级配额（D5/V8：直连入口同样按 用户×用途×日 校验，超额拒绝）
        if (!userQuotaService.tryAcquire(userId, purpose)) {
            throw new BizException(ErrorCode.AI_QUOTA_EXCEEDED);
        }
        // ② 预算判定（生成类用途 EXCEEDED 停新请求，明确错误码不静默）
        AiBudgetDecision decision = budgetService.check(purpose);
        if (decision == AiBudgetDecision.EXCEEDED) {
            log.warn("直连入口预算超限拒绝 purpose={} requestId={}", purpose.getCode(), requestId);
            metrics.recordQuotaExceeded(purpose.getCode());
            throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED);
        }
        // ③ 建议缓存回查（P7：命中直接返回，幂等重试不重复计量）
        var cached = cacheService.get(requestId);
        if (cached.isPresent()) {
            log.info("直连入口建议缓存命中 requestId={} purpose={}", requestId, purpose.getCode());
            return cached.get();
        }
        // ④ 开启调用上下文执行管道（callSeq 从 1 起）
        AgentCallContext context = AgentCallContext.begin(requestId, purpose, userId, scene);
        try {
            XiaoyueSuggestionResponse response = pipeline.run(context);
            // ⑤ 成功建议写缓存（降级失败提示不缓存，重试走完整链路）
            if (!response.isDegraded()) {
                cacheService.put(requestId, response);
            }
            return response;
        } finally {
            AgentCallContext.clear();
        }
    }

    /**
     * 直连请求基本校验（requestId/articleId 必传）
     *
     * <p>【通俗说明】摘要/标签类直连请求的基础参数校验。</p>
     */
    private void validateSuggestRequest(XiaoyueSuggestRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请求体不能为空");
        }
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        if (request.getArticleId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "articleId 必传");
        }
    }

    /**
     * 润色/结构分析直连请求校验（板块三 3.3）：requestId 必传；
     * 润色 articleId/message 至少一项（无稿件可润色选中文本）；结构分析 articleId 必传（基于全文）
     *
     * <p>【通俗说明】润色"有没有东西可润"至少占一样；结构分析"必须有文章可分析"。</p>
     */
    private void validateChatRequestForDirect(XiaoyueChatRequest request, boolean polishMode) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请求体不能为空");
        }
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        if (polishMode) {
            // 润色：无稿件时必须有润色对象文本
            if (request.getArticleId() == null && (request.getMessage() == null || request.getMessage().isBlank())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "articleId 与 message 至少传一项");
            }
        } else {
            // 结构分析：必须关联稿件（基于全文分析）
            if (request.getArticleId() == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "articleId 必传");
            }
        }
    }

    /**
     * 润色固定管道（板块三 3.3）：取稿（可无稿件）→ 组 prompt → 单次调用（回喂重试 1 次）→ 校验 → 计量留痕
     *
     * <p>【通俗说明】润色流程：关联了稿件就取稿（取失败转降级提示），把润色对象
     * （选中文本）和稿件内容拼进模板后走结构化通用骨架。</p>
     */
    private XiaoyueSuggestionResponse runPolishPipeline(AgentCallContext context, XiaoyueChatRequest request, int degraded) {
        // ① 关联稿件时回查（可无稿件——润色对象为选中文本）
        ContentDTO draft = request.getArticleId() == null ? null : fetchDraft(request.getArticleId());
        if (request.getArticleId() != null && draft == null) {
            return degradedResponse(context, AiPurposeEnum.POLISH, "稿件获取失败，无法生成润色建议");
        }
        // ② 组装固定管道 prompt（润色对象=message 选中段落；无稿件时标题/正文占位）
        String originalTask = String.format(AiPromptConstants.POLISH_DIRECT_PROMPT,
                request.getMessage() == null ? "" : request.getMessage(),
                draft == null ? "（未关联稿件）" : draft.getTitle(),
                draft == null ? "（未关联稿件）" : draft.getBody());
        return runStructuredPipeline(context, AiPurposeEnum.POLISH, originalTask, degraded,
                suggestion -> {
                    XiaoyueSuggestionResponse resp = baseResponse(context, AiPurposeEnum.POLISH, degraded);
                    resp.setPolished(suggestion.getPolished());
                    return resp;
                });
    }

    /**
     * 结构分析固定管道（板块三 3.3）：取稿 → 组 prompt → 单次调用（回喂重试 1 次）→ 校验 → 计量留痕
     *
     * <p>【通俗说明】结构分析流程：取稿件（必须成功）→ 把标题正文和用户的补充要求
     * 拼进模板 → 走结构化通用骨架。</p>
     */
    private XiaoyueSuggestionResponse runStructurePipeline(AgentCallContext context, XiaoyueChatRequest request, int degraded) {
        // ① 回查稿件（结构分析必关联稿件，入口已校验）
        ContentDTO draft = fetchDraft(request.getArticleId());
        if (draft == null) {
            return degradedResponse(context, AiPurposeEnum.STRUCTURE, "稿件获取失败，无法生成结构分析建议");
        }
        // ② 组装固定管道 prompt（用户补充要求随 message 传入，可空）
        String originalTask = String.format(AiPromptConstants.STRUCTURE_DIRECT_PROMPT,
                draft.getTitle(), draft.getBody());
        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            originalTask = originalTask + "\n用户补充要求：" + request.getMessage();
        }
        return runStructuredPipeline(context, AiPurposeEnum.STRUCTURE, originalTask, degraded,
                suggestion -> {
                    XiaoyueSuggestionResponse resp = baseResponse(context, AiPurposeEnum.STRUCTURE, degraded);
                    resp.setAnalysis(suggestion.getAnalysis());
                    return resp;
                });
    }

    /**
     * 摘要固定管道：取稿 → 组 prompt → 单次调用（失败回喂重试 1 次）→ 校验 → 计量留痕
     *
     * <p>【通俗说明】摘要流程：取稿件（失败直接返回降级提示，不浪费模型调用）→
     * 按字数上限模板拼 prompt → 走结构化通用骨架。</p>
     */
    private XiaoyueSuggestionResponse runSummaryPipeline(AgentCallContext context, Long articleId, int degraded) {
        // ① 回查稿件：失败直接降级提示（无模型调用无成本）
        ContentDTO draft = fetchDraft(articleId);
        if (draft == null) {
            return degradedResponse(context, AiPurposeEnum.SUMMARY, "稿件获取失败，无法生成摘要");
        }
        // ② 组装固定管道 prompt（模板集中管理）
        String originalTask = String.format(AiPromptConstants.SUMMARY_DIRECT_PROMPT,
                agentProperties.getSummaryMaxChars(), draft.getTitle(), draft.getBody());
        return runStructuredPipeline(context, AiPurposeEnum.SUMMARY, originalTask, degraded,
                suggestion -> {
                    XiaoyueSuggestionResponse resp = baseResponse(context, AiPurposeEnum.SUMMARY, degraded);
                    resp.setSummary(suggestion.getSummary());
                    return resp;
                });
    }

    /**
     * 标签固定管道：取稿 → 组 prompt → 单次调用（越池/解析失败回喂重试 1 次）→ 校验 → 计量留痕
     *
     * <p>【通俗说明】打标流程：取稿件 → 按标签数量上限模板拼 prompt → 走结构化
     * 通用骨架（标签越池校验由校验器负责）。</p>
     */
    private XiaoyueSuggestionResponse runTagPipeline(AgentCallContext context, Long articleId, int degraded) {
        ContentDTO draft = fetchDraft(articleId);
        if (draft == null) {
            return degradedResponse(context, AiPurposeEnum.TAGGING, "稿件获取失败，无法生成标签建议");
        }
        String originalTask = String.format(AiPromptConstants.TAGGING_DIRECT_PROMPT,
                agentProperties.getMaxTagCount(), draft.getTitle(), draft.getBody());
        return runStructuredPipeline(context, AiPurposeEnum.TAGGING, originalTask, degraded,
                suggestion -> {
                    XiaoyueSuggestionResponse resp = baseResponse(context, AiPurposeEnum.TAGGING, degraded);
                    resp.setTags(suggestion.getTags());
                    return resp;
                });
    }

    /**
     * 问答固定管道（降级专用）：无检索直接回答，非 JSON 自由文本
     *
     * <p>【通俗说明】不检索知识库，直接把用户问题发给模型要自然语言回答（非空即合格）；
     * 失败转降级提示。温度 0 保证回答稳定。</p>
     */
    private XiaoyueSuggestionResponse runQaPipeline(AgentCallContext context, String message, int degraded) {
        String question = message == null || message.isBlank() ? "请总结该文章的核心内容" : message;
        int callSeq = context.nextCallSeq();
        long start = System.currentTimeMillis();
        try {
            // 固定管道单次调用：system 用小悦角色边界，user 为用户问题
            TextChatResult result = textModelProvider.chat(TextChatRequest.builder()
                    .systemPrompt(AiPromptConstants.XIAOYUE_SYSTEM_PROMPT)
                    .userPrompt(question)
                    .temperature(0.0D)
                    .build());
            recordCallSuccess(context, callSeq, result, degraded);
            // 校验：非空即可（结构化回答口径随板块四收紧）
            SuggestionValidator.ValidatedSuggestion suggestion = validator.validate(AiPurposeEnum.QA, result.getContent());
            metrics.recordGeneration(normalizeScene(context.getScene()), AiMetrics.RESULT_SUCCESS);
            XiaoyueSuggestionResponse resp = baseResponse(context, AiPurposeEnum.QA, degraded);
            resp.setAnswer(suggestion.getAnswer());
            return resp;
        } catch (BizException e) {
            // 模型通道失败：失败也留明细，降级为失败提示（降级链路不抛异常）
            recordCallFail(context, callSeq, degraded);
            metrics.recordGeneration(normalizeScene(context.getScene()), AiMetrics.RESULT_FAIL);
            return degradedResponse(context, AiPurposeEnum.QA, DEGRADED_MESSAGE);
        } catch (IllegalArgumentException e) {
            // 罕见（自由文本非空校验几乎不失败）：按终态失败处理，不再重试
            log.warn("问答降级输出校验未通过 requestId={} error={}", context.getRequestId(), e.getMessage());
            metrics.recordGeneration(normalizeScene(context.getScene()), AiMetrics.RESULT_FAIL);
            return degradedResponse(context, AiPurposeEnum.QA, DEGRADED_MESSAGE);
        } finally {
            metrics.recordLatency(AiPurposeEnum.QA.getCode(), System.currentTimeMillis() - start);
        }
    }

    /**
     * 结构化管道共用骨架（按 purpose 校验）：委托自定义校验器版本
     *
     * <p>【通俗说明】简化重载：校验规则直接按用途查通用校验器，其余同自定义版。</p>
     */
    private XiaoyueSuggestionResponse runStructuredPipeline(AgentCallContext context, AiPurposeEnum purpose,
                                                            String originalTask, int degraded,
                                                            java.util.function.Function<SuggestionValidator.ValidatedSuggestion,
                                                                    XiaoyueSuggestionResponse> builder) {
        return runStructuredPipeline(context, purpose, originalTask, degraded,
                raw -> validator.validate(purpose, raw), builder);
    }

    /**
     * 结构化管道共用骨架：自校验回喂循环（重试预算固定 1 次）+ 每轮计量（校验器由调用方指定，
     * 板块十五改写/草稿按粒度与字数上限校验，其余按 purpose 通用校验）
     *
     * <p>【循环语义】首轮失败 → 把具体校验错误回喂模型重试 1 次 → 仍失败降级为失败提示，
     * 不落库；每次 provider 调用（含失败）均按 callSeq 落明细。</p>
     *
     * @param context      调用上下文
     * @param purpose      用途
     * @param originalTask 首轮完整任务 prompt
     * @param degraded     降级标记（直连 0 / 降级链路 1）
     * @param validatorFn  输出校验函数（校验不过抛 IllegalArgumentException，异常消息回喂模型）
     * @param builder      校验通过后的响应装配器
     * @return 建议响应（终态：成功或失败提示）
     *
     * <p>【通俗说明】所有固定管道的公共骨架："调模型 → 校验 → 不过就带着错误回喂
     * 重试（最多 1 次）→ 还不过返回失败提示"。每一轮调用无论成败都记账（callSeq
     * 递增），温度固定 0 保证 JSON 输出稳定。校验器和响应装配器由各管道自己注入，
     * 骨架只管循环和记账。</p>
     */
    private XiaoyueSuggestionResponse runStructuredPipeline(AgentCallContext context, AiPurposeEnum purpose,
                                                            String originalTask, int degraded,
                                                            java.util.function.Function<String,
                                                                    SuggestionValidator.ValidatedSuggestion> validatorFn,
                                                            java.util.function.Function<SuggestionValidator.ValidatedSuggestion,
                                                                    XiaoyueSuggestionResponse> builder) {
        // 重试预算 = 1 次首轮 + structuredRetryBudget 次回喂重试（固定防反思循环成本放大）
        int totalAttempts = 1 + agentProperties.getStructuredRetryBudget();
        String lastError = null;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            int callSeq = context.nextCallSeq();
            long start = System.currentTimeMillis();
            // 首轮用原始任务 prompt；回喂轮用校验错误反馈模板（最小反思闭环）
            String userPrompt = attempt == 1 ? originalTask : String.format(
                    AiPromptConstants.STRUCTURED_RETRY_FEEDBACK, lastError, originalTask);
            try {
                // 固定管道单次模型调用（温度 0 保证结构化输出确定性）
                TextChatResult result = textModelProvider.chat(TextChatRequest.builder()
                        .systemPrompt(AiPromptConstants.XIAOYUE_SYSTEM_PROMPT)
                        .userPrompt(userPrompt)
                        .temperature(0.0D)
                        .build());
                recordCallSuccess(context, callSeq, result, degraded);
                metrics.recordGeneration(normalizeScene(context.getScene()), AiMetrics.RESULT_SUCCESS);
                // service 侧校验（不依赖模型自觉）：不过则记错误回喂下一轮（校验器由调用方指定）
                SuggestionValidator.ValidatedSuggestion suggestion = validatorFn.apply(result.getContent());
                return builder.apply(suggestion);
            } catch (BizException e) {
                // 模型通道失败：失败也留明细；重试预算内再试，预算耗尽降级失败提示
                recordCallFail(context, callSeq, degraded);
                metrics.recordGeneration(normalizeScene(context.getScene()), AiMetrics.RESULT_FAIL);
                lastError = "模型调用失败：" + e.getMessage();
            } catch (IllegalArgumentException e) {
                // 校验不过：模型调用本身成功（已计量），记录具体错误回喂重试
                log.info("结构化输出校验未通过 requestId={} purpose={} attempt={} error={}",
                        context.getRequestId(), purpose.getCode(), attempt, e.getMessage());
                metrics.recordGeneration(normalizeScene(context.getScene()), AiMetrics.RESULT_FAIL);
                lastError = e.getMessage();
            } finally {
                metrics.recordLatency(purpose.getCode(), System.currentTimeMillis() - start);
            }
        }
        // 重试预算耗尽：降级为失败提示，不落库（2.4 失败语义）
        log.warn("结构化生成终态失败（重试预算耗尽）requestId={} purpose={}", context.getRequestId(), purpose.getCode());
        return degradedResponse(context, purpose, DEGRADED_MESSAGE);
    }

    /**
     * 回查稿件标题与正文（失败返回 null，由调用方降级处理）
     *
     * @param articleId 文章 ID
     * @return 内容 DTO；不存在/服务异常返回 null
     *
     * <p>【通俗说明】去 content 服务取稿件的标题和正文，取不到返回 null 由调用方
     * 转降级提示（日志只记正文长度不打正文）。</p>
     */
    private ContentDTO fetchDraft(Long articleId) {
        try {
            Result<ContentDTO> resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, articleId);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                log.warn("直连管道取稿失败 articleId={}", articleId);
                return null;
            }
            // 日志纪律：只记长度，不打印正文
            log.info("直连管道取稿成功 articleId={} bodyChars={}", articleId,
                    resp.getData().getBody() == null ? 0 : resp.getData().getBody().length());
            return resp.getData();
        } catch (Exception e) {
            log.warn("直连管道取稿异常 articleId={}", articleId, e);
            return null;
        }
    }

    /**
     * 成功调用计量留痕（明细 + 调用计数指标）
     *
     * <p>【通俗说明】成功记账：token 取模型返回值，降级标记如实记录。</p>
     */
    private void recordCallSuccess(AgentCallContext context, int callSeq, TextChatResult result, int degraded) {
        usageRecordService.recordSuccess(AiUsageDetail.builder()
                .requestId(context.getRequestId())
                .callSeq(callSeq)
                .userId(context.getUserId())
                .purpose(context.getPurpose())
                .scene(context.getScene())
                .modelVersion(result.getModelVersion())
                .tokenIn(result.getTokenIn())
                .tokenOut(result.getTokenOut())
                .degraded(degraded)
                .occurredAt(LocalDateTime.now())
                .build());
        metrics.recordCall(context.getPurpose().getCode(), providerProperties.getProviderName(), AiMetrics.RESULT_SUCCESS);
    }

    /**
     * 失败调用计量留痕（失败即无成本防护：token 按 0 落库）
     *
     * <p>【通俗说明】失败记账：token 未知按 0，保证账本连续。</p>
     */
    private void recordCallFail(AgentCallContext context, int callSeq, int degraded) {
        usageRecordService.recordFail(AiUsageDetail.builder()
                .requestId(context.getRequestId())
                .callSeq(callSeq)
                .userId(context.getUserId())
                .purpose(context.getPurpose())
                .scene(context.getScene())
                .modelVersion(providerProperties.getModel())
                .tokenIn(null)
                .tokenOut(null)
                .degraded(degraded)
                .occurredAt(LocalDateTime.now())
                .build());
        metrics.recordCall(context.getPurpose().getCode(), providerProperties.getProviderName(), AiMetrics.RESULT_FAIL);
    }

    /**
     * 构建成功建议响应骨架（D3/D13：带 AI 生成标识与提示文案）
     *
     * <p>【通俗说明】成功响应的公共字段打包（ requestId/用途/AI 标识/提示文案）。</p>
     */
    private XiaoyueSuggestionResponse baseResponse(AgentCallContext context, AiPurposeEnum purpose, int degraded) {
        return XiaoyueSuggestionResponse.builder()
                .requestId(context.getRequestId())
                .purpose(purpose.getCode())
                .aiGenerated(true)
                .degraded(degraded == 1)
                .message(AI_NOTICE_MESSAGE)
                .modelVersion(providerProperties.getModel())
                .build();
    }

    /**
     * 构建降级失败响应（D4：不落库；message 提示可手动填写）
     *
     * <p>【通俗说明】失败提示响应：aiGenerated=false、degraded=true，建议前端让
     * 用户手动填写。</p>
     */
    private XiaoyueSuggestionResponse degradedResponse(AgentCallContext context, AiPurposeEnum purpose, String message) {
        return XiaoyueSuggestionResponse.builder()
                .requestId(context.getRequestId())
                .purpose(purpose.getCode())
                .aiGenerated(false)
                .degraded(true)
                .message(message)
                .modelVersion(providerProperties.getModel())
                .build();
    }

    /**
     * 场景标签归一化（null 兜底 default，防指标空标签）
     *
     * <p>【通俗说明】场景没传就记成 default，避免监控指标出现空标签。</p>
     */
    private String normalizeScene(String scene) {
        return scene == null || scene.isBlank() ? AiMetrics.SCENE_DEFAULT : scene;
    }

    /**
     * 管道执行体函数式接口（直连入口统一前置的回调）
     *
     * <p>【通俗说明】"具体怎么干"的回调约定：统一前置检查做完后，调它执行实际管道。</p>
     */
    @FunctionalInterface
    private interface Pipeline {
        /**
         * 执行管道
         *
         * @param context 调用上下文
         * @return 建议响应
         */
        XiaoyueSuggestionResponse run(AgentCallContext context);
    }
}
