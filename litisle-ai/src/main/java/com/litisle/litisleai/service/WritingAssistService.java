package com.litisle.litisleai.service;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.agent.AgentCallContext;
import com.litisle.litisleai.agent.XiaoyueAgent;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.constant.AiContentConstant;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.dto.DraftRequest;
import com.litisle.litisleai.dto.RewriteRequest;
import com.litisle.litisleai.dto.XiaoyueSuggestionResponse;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.service.support.SuggestionValidator;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.feign.ContentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 写作辅助扩展编排服务（板块十五：15.1 多级改写建议 + 15.2 文章草稿生成，F6 建议态延伸）
 *
 * <p>【职责边界】本类承载写作辅助的 Agent 优先编排（意图指令 → Agent 循环 → 结构化校验
 * 回喂重试 1 次 → 失败降级直连固定管道——三级降级链复用）；固定管道、计量骨架与降级
 * 通道复用 XiaoyueSuggestionService；意图指令（INTENT_REWRITE/INTENT_DRAFT）在本服务
 * 路由组装（/chat 原入口不新增 purpose，机审用途禁止入口的口径不变）。</p>
 *
 * <p>【计量口径】purpose=POLISH 复用写作辅助桶（F6 科目不扩枚举），scene 区分粒度
 * （rewrite-{粒度}/draft）；配额与预算在路由后即判定（Agent/降级链路均被覆盖）；
 * Agent 每轮计量由 MeteringAgentChatModel 收口，降级管道计量在 XiaoyueSuggestionService
 * 收口；同一 requestId 全链路只扣一次。</p>
 *
 * <p>【铁律】建议态不自动写入（D4，AI 不落库——作者采用后由前端带入 content 正常创建
 * 流程走完整 DFA + 机审主链路）；恒带 AI 标识（D3/D13）；展示前过 DFA 闸门（调用方执行）；
 * 改写保持作者原意（prompt 硬约束）；仅登录用户可用（写作辅助不开匿名）；
 * prompt 版本由 model_version 承载（复用键与评测回归可追溯）。</p>
 *
 * <p>【失败语义】Agent 循环超限（80007）/模型不可用（80005）→ 降级直连固定管道
 * （降级链路同样计量，明细 degraded=1）；降级也失败 → 返回失败提示响应（degraded=true），
 * 不抛异常；articleId 非本人所有/文章不存在 → 明确拒绝（AI-V6 ⑤/⑦ 口径）。</p>
 *
 * <p>【通俗说明】给作者的"写作外挂"：多级改写（改标题/段落/句子/词语，给几个候选
 * 版本+改动理由）和文章草稿生成两大功能。执行策略是"Agent 优先"——先让 Agent
 * 全权处理，输出不合格就把错误回喂让它重试一次；Agent 出问题（循环超限/模型挂了）
 * 就降级为"固定管道直连"，再不行就返回"暂时无法生成，可手动填写"的提示——
 * 绝不把异常抛给用户。安全底线：只服务登录用户、只能改自己的文章、AI 建议绝不
 * 自动写入文章（作者采用后仍走正常的创建+审核流程）。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WritingAssistService {

    /** AI 生成提示文案（D3/D13） */
    private static final String AI_NOTICE_MESSAGE = "AI 生成内容，仅供参考，请确认后再保存";

    /** 小悦 Agent（enabled=false 时 Bean 缺席，走降级直连固定管道） */
    private final ObjectProvider<XiaoyueAgent> agentProvider;

    /** 直连管道服务（降级通道实现：固定管道 + 计量骨架复用） */
    private final XiaoyueSuggestionService suggestionService;

    /** 结构化建议校验器（15.1 按粒度 / 15.2 按字数上限扩展校验） */
    private final SuggestionValidator validator;

    /** 建议态缓存（P7：命中直接回查，不重复计量） */
    private final SuggestionCacheService cacheService;

    /** 预算分账判定（POLISH 写作辅助桶） */
    private final AiBudgetService budgetService;

    /** 用户级配额校验（D5/V8：用户×用途×日） */
    private final AiUserQuotaService userQuotaService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** 通道配置（model 版本快照） */
    private final AiProviderProperties providerProperties;

    /** Agent 配置（候选数上限/字数上限来源） */
    private final AiAgentProperties agentProperties;

    /** 内容服务客户端（articleId 入口取稿 + 归属校验） */
    private final ContentClient contentClient;

    /**
     * 多级改写建议（板块十五 15.1）：登录前置 → 入参校验与归一化 → 配额/预算/缓存 →
     * Agent 优先（校验回喂重试 1 次）→ 失败降级直连固定管道
     *
     * <p>【业务逻辑步骤】
     * 1. 登录前置（写作辅助不开匿名）+ requestId 必传 + 粒度白名单校验；
     * 2. 原文定位：articleId（归属校验 + 取稿回填）优先，否则 originalText 必传；
     *    候选数 clamp 到 1~配置上限、风格偏好空值兜底（归一化结果回填请求对象）；
     * 3. 配额/预算判定（POLISH 桶，路由后即判）→ 建议缓存回查（P7 幂等）；
     * 4. Agent 执行：INTENT_REWRITE 意图 → 校验失败回喂重试 1 次 → 仍失败降级直连。</p>
     *
     * @param request 改写请求（requestId/targetType 必传；原文来源二选一）
     * @param userId  当前登录用户 ID（controller 从 Sa-Token 会话取得；null 即拒绝）
     * @return 改写建议响应（degraded=true 时为失败提示，不落库）
     * @throws BizException 未登录（10001）、requestId 缺失（80002）、粒度非法（10001）、
     *                      文章非本人/获取失败（10001）、配额超限（80003）、预算超限（80004）
     *
     * <p>【通俗说明】改写建议七步流水线：验登录 → 验参数 → 定位原文（articleId 或
     * 直接传文本）并归一化候选数 → 查用户配额 → 查平台预算 → 查建议缓存（重试
     * 幂等）→ Agent 执行（内部含校验回喂与降级）。</p>
     */
    public XiaoyueSuggestionResponse rewrite(RewriteRequest request, Long userId) {
        // ① 登录前置：写作辅助不开匿名（计量归属与建议态确认都需要用户身份）
        requireLogin(userId);
        // ② 入参校验：requestId 必传 + 粒度白名单（注册表外粒度拒绝）
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        if (request.getTargetType() == null || !AiContentConstant.TARGET_TYPES.contains(request.getTargetType())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "targetType 仅支持 TITLE/PARAGRAPH/SENTENCE/WORD");
        }
        // ③ 归一化：原文定位（归属校验就地回填）+ 候选数 clamp + 风格偏好空值兜底
        resolveRewriteOriginalText(request, userId);
        request.setCandidateCount(normalizeCandidateCount(request.getCandidateCount()));
        if (request.getStylePreference() == null || request.getStylePreference().isBlank()) {
            request.setStylePreference("无");
        }
        // ④ 用户级配额（D5/V8：POLISH 桶超额拒绝）
        if (!userQuotaService.tryAcquire(userId, AiPurposeEnum.POLISH)) {
            throw new BizException(ErrorCode.AI_QUOTA_EXCEEDED);
        }
        // ⑤ 预算判定（POLISH 写作辅助桶 EXCEEDED 停新请求，明确错误码）
        AiBudgetDecision decision = budgetService.check(AiPurposeEnum.POLISH);
        if (decision == AiBudgetDecision.EXCEEDED) {
            log.warn("改写建议预算超限拒绝 requestId={} targetType={}", request.getRequestId(), request.getTargetType());
            metrics.recordQuotaExceeded(AiPurposeEnum.POLISH.getCode());
            throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED);
        }
        // ⑥ 建议缓存回查（P7：命中直接返回，幂等重试不重复计量）
        var cached = cacheService.get(request.getRequestId());
        if (cached.isPresent()) {
            log.info("改写建议缓存命中 requestId={} targetType={}", request.getRequestId(), request.getTargetType());
            return cached.get();
        }
        // ⑦ Agent 优先执行（一次性请求 memoryId=tmp:requestId，不落会话库；scene 区分粒度）
        AgentCallContext context = AgentCallContext.begin(request.getRequestId(), AiPurposeEnum.POLISH,
                userId, AiContentConstant.rewriteScene(request.getTargetType()));
        try {
            return executeRewriteAgent(request, context);
        } finally {
            AgentCallContext.clear();
        }
    }

    /**
     * 文章草稿生成（板块十五 15.2）：登录前置 → 入参校验 → 配额/预算/缓存 →
     * Agent 优先（字数上限校验回喂重试 1 次）→ 失败降级直连固定管道
     *
     * <p>【采用路径】AI 不落库：草稿仅参考，作者编辑后由前端带入 content 正常创建流程
     * （走完整 DFA 同步闸门 + 机审主链路，AI 草稿不绕过送审）。</p>
     *
     * @param request 草稿请求（requestId/theme 必传；大纲/要点与字数范围可空）
     * @param userId  当前登录用户 ID（null 即拒绝）
     * @return 草稿建议响应（draft 草稿正文 + outline 段落结构建议；degraded=true 为失败提示）
     * @throws BizException 未登录（10001）、requestId 缺失（80002）、主题缺失（10001）、
     *                      配额超限（80003）、预算超限（80004）
     *
     * <p>【通俗说明】草稿生成流水线：验登录和主题 → 查配额与预算 → 查缓存 →
     * Agent 按主题生成草稿和段落结构建议（同样带校验回喂与降级）。生成的草稿只
     * 是参考，不会自动创建文章。</p>
     */
    public XiaoyueSuggestionResponse draft(DraftRequest request, Long userId) {
        // ① 登录前置 + 入参校验：主题必传（草稿围绕主题生成）
        requireLogin(userId);
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        if (request.getTheme() == null || request.getTheme().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "主题（theme）必传");
        }
        // ② 用户级配额 + 预算判定（POLISH 桶，路由后即判）
        if (!userQuotaService.tryAcquire(userId, AiPurposeEnum.POLISH)) {
            throw new BizException(ErrorCode.AI_QUOTA_EXCEEDED);
        }
        AiBudgetDecision decision = budgetService.check(AiPurposeEnum.POLISH);
        if (decision == AiBudgetDecision.EXCEEDED) {
            log.warn("文章草稿预算超限拒绝 requestId={}", request.getRequestId());
            metrics.recordQuotaExceeded(AiPurposeEnum.POLISH.getCode());
            throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED);
        }
        // ③ 建议缓存回查（P7 幂等）
        var cached = cacheService.get(request.getRequestId());
        if (cached.isPresent()) {
            log.info("文章草稿缓存命中 requestId={}", request.getRequestId());
            return cached.get();
        }
        // ④ Agent 优先执行（一次性请求；scene=draft 计量区分）
        AgentCallContext context = AgentCallContext.begin(request.getRequestId(), AiPurposeEnum.POLISH,
                userId, AiContentConstant.SCENE_DRAFT);
        try {
            return executeDraftAgent(request, context);
        } finally {
            AgentCallContext.clear();
        }
    }

    /**
     * 登录前置校验（写作辅助不开匿名：计量归属与建议态确认都需要用户身份）
     *
     * @param userId 当前登录用户 ID
     * @throws BizException 未登录（PARAM_ERROR）
     *
     * <p>【通俗说明】没登录直接拒绝——写作辅助不开匿名。</p>
     */
    private void requireLogin(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "写作辅助仅登录用户可用");
        }
    }

    /**
     * 定位改写原文（就地回填 request.originalText）：articleId 优先——取稿 + 归属校验
     * （AI-V6 ⑤/⑦ 口径：非本人所有明确拒绝，不做他人文章改写）；无 articleId 时
     * originalText 必传（创作页选中文本直传）
     *
     * @param request 改写请求
     * @param userId  当前登录用户 ID（归属比对依据）
     * @throws BizException 文章不存在/获取失败/非本人所有（明确拒绝不降级）
     *
     * <p>【通俗说明】确定"要改的原文是哪段"：传了 articleId 就去取稿（改标题取标题、
     * 其他粒度取正文，且必须是自己文章）；没传 articleId 就必须直接传原文文本。</p>
     */
    private void resolveRewriteOriginalText(RewriteRequest request, Long userId) {
        if (request.getArticleId() != null) {
            // TITLE 粒度原文取标题（承载板块十二 4.7 标题/开头多版建议）；其余粒度取正文
            ContentDTO article = fetchOwnArticle(request.getArticleId(), userId);
            request.setOriginalText(AiContentConstant.TARGET_TYPE_TITLE.equals(request.getTargetType())
                    ? article.getTitle() : article.getBody());
            return;
        }
        if (request.getOriginalText() == null || request.getOriginalText().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "articleId 与 originalText 至少传一项（改写原文必传）");
        }
    }

    /**
     * 取稿 + 归属校验（改写 articleId 入口专用）
     *
     * @param articleId 文章 ID
     * @param userId    当前登录用户 ID
     * @return 内容 DTO（ownerId 与 userId 同人）
     * @throws BizException 文章不存在/获取失败/非本人所有
     *
     * <p>【通俗说明】取稿并验证"这文章是你的"：取不到报错、不是你的也报错——
     * 绝不给别人的文章做改写。</p>
     */
    private ContentDTO fetchOwnArticle(Long articleId, Long userId) {
        Result<ContentDTO> resp;
        try {
            resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, articleId);
        } catch (Exception e) {
            // 取稿通道异常：明确拒绝不静默（改写无原文无法继续）
            log.warn("改写取稿异常 articleId={}", articleId, e);
            throw new BizException(ErrorCode.PARAM_ERROR, "文章获取失败，无法提供改写建议");
        }
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文章不存在或获取失败");
        }
        // 归属校验：非本人所有明确拒绝（AI-V6 ⑤/⑦ 口径）
        if (!userId.equals(resp.getData().getOwnerId())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文章非本人所有，无法提供改写建议");
        }
        return resp.getData();
    }

    /**
     * 候选数归一化：缺省取配置上限；越界 clamp 到 1~上限（防超长输出成本放大）
     *
     * @param candidateCount 请求候选数（可空）
     * @return 归一化后的候选数
     *
     * <p>【通俗说明】候选数没传就用配置默认值；传了就夹在 1~配置上限之间，
     * 防止用户要 100 个候选把成本撑爆。</p>
     */
    private int normalizeCandidateCount(Integer candidateCount) {
        int limit = agentProperties.getRewriteCandidateLimit();
        if (candidateCount == null) {
            return limit;
        }
        return Math.min(Math.max(candidateCount, 1), limit);
    }

    /**
     * 改写 Agent 执行路径：意图指令 → Agent 循环 → 校验回喂重试 1 次 → 异常降级直连
     *
     * <p>【通俗说明】把改写意图组装成指令交给 Agent 执行；Agent 没装配（功能未开）
     * 或执行中循环超限/模型不可用，就转固定管道降级。</p>
     */
    private XiaoyueSuggestionResponse executeRewriteAgent(RewriteRequest request, AgentCallContext context) {
        XiaoyueAgent agent = agentProvider.getIfAvailable();
        // Agent Bean 缺席（模型通道未启用）：直接降级直连（固定管道内部降级为失败提示）
        if (agent == null) {
            return fallbackRewrite(request, context);
        }
        String intentMessage = String.format(AiPromptConstants.INTENT_REWRITE,
                AiContentConstant.targetTypeLabel(request.getTargetType()),
                request.getOriginalText(),
                request.getStylePreference(),
                request.getCandidateCount(),
                agentProperties.getRewriteReasonMaxChars(),
                agentProperties.getRewriteTitleMaxChars());
        try {
            String raw = agent.chat("tmp:" + request.getRequestId(), intentMessage);
            return finalizeRewrite(request, context, intentMessage, raw, 1);
        } catch (BizException e) {
            // 降级铁律：循环超限/模型不可用 → 降级为固定管道直连（降级链路同样计量）
            if (e.getCode() == ErrorCode.AI_AGENT_LOOP_EXCEEDED.getCode()
                    || e.getCode() == ErrorCode.AI_PROVIDER_UNAVAILABLE.getCode()) {
                log.warn("改写 Agent 执行异常触发降级 requestId={} targetType={} code={}",
                        request.getRequestId(), request.getTargetType(), e.getCode());
                return fallbackRewrite(request, context);
            }
            throw e;
        }
    }

    /**
     * 改写输出校验收口：通过即返回；不过则回喂重试 1 次；仍不过降级直连固定管道
     *
     * <p>【通俗说明】检查 Agent 交的改写作业：合格就打包响应并写缓存；不合格把
     * 具体错误回喂让 Agent 重做一次，还不合格就转固定管道；重试中通道再挂也走降级。</p>
     */
    private XiaoyueSuggestionResponse finalizeRewrite(RewriteRequest request, AgentCallContext context,
                                                      String intentMessage, String raw, int attempt) {
        try {
            SuggestionValidator.ValidatedSuggestion suggestion = validator.validateRewrite(
                    raw, request.getTargetType(), request.getCandidateCount(),
                    agentProperties.getRewriteReasonMaxChars(), agentProperties.getRewriteTitleMaxChars(),
                    request.getOriginalText());
            // 校验通过：组装成功响应并写建议缓存（P7）
            XiaoyueSuggestionResponse response = buildRewriteResponse(context, request, suggestion);
            cacheService.put(request.getRequestId(), response);
            return response;
        } catch (IllegalArgumentException e) {
            if (attempt > 1) {
                // 已是回喂轮仍失败：降级直连固定管道（三级降级链复用）
                log.warn("改写输出自校验重试后仍失败，降级直连 requestId={} targetType={} error={}",
                        request.getRequestId(), request.getTargetType(), e.getMessage());
                return fallbackRewrite(request, context);
            }
            log.info("改写输出校验未通过，回喂重试 requestId={} targetType={} error={}",
                    request.getRequestId(), request.getTargetType(), e.getMessage());
            try {
                XiaoyueAgent agent = agentProvider.getIfAvailable();
                // 回喂轮（callSeq 由计量装饰模型递增；Agent 缺席走降级）
                String retryRaw = agent == null ? null : agent.chat("tmp:" + request.getRequestId(),
                        String.format(AiPromptConstants.STRUCTURED_RETRY_FEEDBACK, e.getMessage(), intentMessage));
                if (retryRaw == null) {
                    return fallbackRewrite(request, context);
                }
                return finalizeRewrite(request, context, intentMessage, retryRaw, attempt + 1);
            } catch (BizException ex) {
                if (ex.getCode() == ErrorCode.AI_AGENT_LOOP_EXCEEDED.getCode()
                        || ex.getCode() == ErrorCode.AI_PROVIDER_UNAVAILABLE.getCode()) {
                    return fallbackRewrite(request, context);
                }
                throw ex;
            }
        }
    }

    /**
     * 改写降级直连：解除循环约束后转固定管道（callSeq 连续累计，明细 degraded=1）
     *
     * <p>【通俗说明】放弃 Agent，改走 XiaoyueSuggestionService 的固定 prompt 流程。</p>
     */
    private XiaoyueSuggestionResponse fallbackRewrite(RewriteRequest request, AgentCallContext context) {
        context.leaveAgentPhase();
        return suggestionService.rewriteFallback(request, context);
    }

    /**
     * 草稿 Agent 执行路径：意图指令 → Agent 循环 → 字数上限校验回喂重试 1 次 → 异常降级直连
     *
     * <p>【通俗说明】把草稿意图（主题/大纲/字数范围）组装成指令交给 Agent；Agent
     * 缺席或异常（循环超限/模型不可用）转固定管道降级。</p>
     */
    private XiaoyueSuggestionResponse executeDraftAgent(DraftRequest request, AgentCallContext context) {
        XiaoyueAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            return fallbackDraft(request, context);
        }
        String intentMessage = String.format(AiPromptConstants.INTENT_DRAFT,
                request.getTheme(),
                request.getOutline() == null || request.getOutline().isBlank() ? "无" : request.getOutline(),
                request.wordRangeText(),
                agentProperties.getDraftMaxChars());
        try {
            String raw = agent.chat("tmp:" + request.getRequestId(), intentMessage);
            return finalizeDraft(request, context, intentMessage, raw, 1);
        } catch (BizException e) {
            if (e.getCode() == ErrorCode.AI_AGENT_LOOP_EXCEEDED.getCode()
                    || e.getCode() == ErrorCode.AI_PROVIDER_UNAVAILABLE.getCode()) {
                log.warn("草稿 Agent 执行异常触发降级 requestId={} code={}", request.getRequestId(), e.getCode());
                return fallbackDraft(request, context);
            }
            throw e;
        }
    }

    /**
     * 草稿输出校验收口：通过即返回；不过则回喂重试 1 次；仍不过降级直连固定管道
     *
     * <p>【通俗说明】与改写版同构：草稿校验合格打包写缓存，不合格回喂重试一次，
     * 还不合格转固定管道。</p>
     */
    private XiaoyueSuggestionResponse finalizeDraft(DraftRequest request, AgentCallContext context,
                                                    String intentMessage, String raw, int attempt) {
        try {
            SuggestionValidator.ValidatedSuggestion suggestion = validator.validateDraft(
                    raw, agentProperties.getDraftMaxChars());
            XiaoyueSuggestionResponse response = buildDraftResponse(context, request, suggestion);
            cacheService.put(request.getRequestId(), response);
            return response;
        } catch (IllegalArgumentException e) {
            if (attempt > 1) {
                log.warn("草稿输出自校验重试后仍失败，降级直连 requestId={} error={}",
                        request.getRequestId(), e.getMessage());
                return fallbackDraft(request, context);
            }
            log.info("草稿输出校验未通过，回喂重试 requestId={} error={}", request.getRequestId(), e.getMessage());
            try {
                XiaoyueAgent agent = agentProvider.getIfAvailable();
                String retryRaw = agent == null ? null : agent.chat("tmp:" + request.getRequestId(),
                        String.format(AiPromptConstants.STRUCTURED_RETRY_FEEDBACK, e.getMessage(), intentMessage));
                if (retryRaw == null) {
                    return fallbackDraft(request, context);
                }
                return finalizeDraft(request, context, intentMessage, retryRaw, attempt + 1);
            } catch (BizException ex) {
                if (ex.getCode() == ErrorCode.AI_AGENT_LOOP_EXCEEDED.getCode()
                        || ex.getCode() == ErrorCode.AI_PROVIDER_UNAVAILABLE.getCode()) {
                    return fallbackDraft(request, context);
                }
                throw ex;
            }
        }
    }

    /**
     * 草稿降级直连：解除循环约束后转固定管道（callSeq 连续累计，明细 degraded=1）
     *
     * <p>【通俗说明】放弃 Agent，改走固定 prompt 流程生成草稿。</p>
     */
    private XiaoyueSuggestionResponse fallbackDraft(DraftRequest request, AgentCallContext context) {
        context.leaveAgentPhase();
        return suggestionService.draftFallback(request, context);
    }

    /**
     * 组装改写成功响应（D3/D13：带 AI 生成标识；D4：建议态不自动写入）
     *
     * <p>【通俗说明】把候选版本、优化建议、AI 生成提示等打包成响应。</p>
     */
    private XiaoyueSuggestionResponse buildRewriteResponse(AgentCallContext context, RewriteRequest request,
                                                           SuggestionValidator.ValidatedSuggestion suggestion) {
        XiaoyueSuggestionResponse response = XiaoyueSuggestionResponse.builder()
                .requestId(context.getRequestId())
                .purpose(AiPurposeEnum.POLISH.getCode())
                .targetType(request.getTargetType())
                .rewriteCandidates(suggestion.getRewriteCandidates())
                .rewriteSuggestions(suggestion.getRewriteSuggestions())
                .aiGenerated(true)
                .degraded(false)
                .message(AI_NOTICE_MESSAGE)
                .modelVersion(providerProperties.getModel())
                .build();
        log.info("改写建议生成成功 requestId={} targetType={} candidates={} suggestions={}",
                context.getRequestId(), request.getTargetType(),
                suggestion.getRewriteCandidates() == null ? 0 : suggestion.getRewriteCandidates().size(),
                suggestion.getRewriteSuggestions() == null ? 0 : suggestion.getRewriteSuggestions().size());
        return response;
    }

    /**
     * 组装草稿成功响应（D3/D13：带 AI 生成标识；AI 不落库——采用走 content 正常创建流程）
     *
     * <p>【通俗说明】把草稿正文、段落结构建议、AI 生成提示打包成响应（日志只记长度
     * 不打印正文）。</p>
     */
    private XiaoyueSuggestionResponse buildDraftResponse(AgentCallContext context, DraftRequest request,
                                                         SuggestionValidator.ValidatedSuggestion suggestion) {
        // 日志纪律：只记长度，不打印草稿正文
        XiaoyueSuggestionResponse response = XiaoyueSuggestionResponse.builder()
                .requestId(context.getRequestId())
                .purpose(AiPurposeEnum.POLISH.getCode())
                .draft(suggestion.getDraft())
                .outline(suggestion.getOutline())
                .aiGenerated(true)
                .degraded(false)
                .message(AI_NOTICE_MESSAGE)
                .modelVersion(providerProperties.getModel())
                .build();
        log.info("文章草稿生成成功 requestId={} themeChars={} draftChars={}",
                context.getRequestId(), request.getTheme().length(),
                suggestion.getDraft() == null ? 0 : suggestion.getDraft().length());
        return response;
    }
}
