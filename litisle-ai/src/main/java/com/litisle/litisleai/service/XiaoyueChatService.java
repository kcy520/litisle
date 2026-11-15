package com.litisle.litisleai.service;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.agent.AgentCallContext;
import com.litisle.litisleai.agent.MeteringAgentChatModel;
import com.litisle.litisleai.agent.XiaoyueAgent;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.dto.XiaoyueChatRequest;
import com.litisle.litisleai.dto.XiaoyueSuggestionResponse;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.service.support.SuggestionValidator;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 小悦统一入口编排服务（板块二 2.3：POST /ai/xiaoyue/chat 意图路由 + 降级直连；板块三 3.3 接入会话记忆与润色/结构分析）
 *
 * <p>【意图路由口径】P6 定档：purpose 由功能入口预传入，Agent 循环继承，不做路由 LLM 调用；
 * 本服务按 purpose 组装意图指令后交 Agent 执行（Agent 自主决定调哪个工具、调几次）。</p>
 *
 * <p>【会话口径（板块三 3.1/3.2）】sessionId 非空：归属校验 → USER 消息落库（ai_message 全量存档）
 * → Agent 以 sessionId 为 memoryId 走 Redis 窗口记忆 → 终答 ASSISTANT 落库（带轮次 token 对账）；
 * sessionId 为空：一次性请求（memoryId=一次性 requestId 键，不落 ai_session/ai_message）。</p>
 *
 * <p>【降级铁律】Agent 循环超限（80007）/模型不可用（80005）→ 解除循环约束（callSeq 连续累计）
 * → 降级为固定管道直连（XiaoyueSuggestionService，降级链路同样计量，明细 degraded=1）；
 * 降级也失败 → 返回「暂时无法生成」降级响应（可手动填写，不落库），不抛异常。</p>
 *
 * <p>【结构化自校验（2.4）】SUMMARY/TAGGING/POLISH/STRUCTURE 要求 JSON 输出：解析失败或校验
 * 不过把具体错误回喂模型重试 1 次；仍失败降级为失败提示，不落库。校验规则在 service 侧执行。</p>
 *
 * <p>【计量承诺】配额与预算在路由后即判定（Agent/降级链路均被覆盖）；Agent 每轮计量由
 * {@link MeteringAgentChatModel} 收口，降级管道计量在 XiaoyueSuggestionService 收口；
 * 同一 requestId 全链路只扣一次（配额判定式读取日汇总，计量明细按轮落库）。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
public class XiaoyueChatService {

    /** 入口支持的用途集合（板块三扩充 POLISH/STRUCTURE；机审用途禁止走本入口） */
    private static final Set<String> SUPPORTED_PURPOSES = Set.of(
            AiPurposeEnum.SUMMARY.getCode(), AiPurposeEnum.TAGGING.getCode(), AiPurposeEnum.QA.getCode(),
            AiPurposeEnum.POLISH.getCode(), AiPurposeEnum.STRUCTURE.getCode());

    /** AI 生成提示文案（D3/D13） */
    private static final String AI_NOTICE_MESSAGE = "AI 生成内容，仅供参考，请确认后再保存";

    /** 降级失败提示文案（可手动填写，不落库） */
    private static final String DEGRADED_MESSAGE = "AI 暂时无法生成该内容，请稍后重试或手动填写";

    /** 小悦 Agent（enabled=false 时 Bean 缺席，调用抛 80005） */
    private final ObjectProvider<XiaoyueAgent> agentProvider;

    /** 直连管道服务（降级通道实现） */
    private final XiaoyueSuggestionService suggestionService;

    /** 结构化建议校验器（Agent 输出与直连管道共用同一套校验规则） */
    private final SuggestionValidator validator;

    /** 建议态缓存（P7：命中直接回查，不重复计量） */
    private final SuggestionCacheService cacheService;

    /** 预算分账判定 */
    private final AiBudgetService budgetService;

    /** 用户级配额校验（板块二接线：1.3 遗留项落地） */
    private final AiUserQuotaService userQuotaService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** 通道配置（model 版本快照） */
    private final AiProviderProperties providerProperties;

    /** Agent 配置（摘要字数上限 / 标签数量上限，prompt 组装来源） */
    private final AiAgentProperties agentProperties;

    /** 会话治理服务（板块三 3.1：归属校验/消息落库/活跃刷新） */
    private final AiSessionService sessionService;

    /** RAG 配置（匿名问答日上限来源，板块四 4.2/P5） */
    private final com.litisle.litisleai.config.AiRagProperties ragProperties;

    /**
     * 构造注入（Agent 按需取用：通道未启用时 Bean 缺席不阻断启动）
     */
    public XiaoyueChatService(ObjectProvider<XiaoyueAgent> agentProvider,
                              XiaoyueSuggestionService suggestionService,
                              SuggestionValidator validator,
                              SuggestionCacheService cacheService,
                              AiBudgetService budgetService,
                              AiUserQuotaService userQuotaService,
                              AiMetrics metrics,
                              AiProviderProperties providerProperties,
                              AiAgentProperties agentProperties,
                              AiSessionService sessionService,
                              com.litisle.litisleai.config.AiRagProperties ragProperties) {
        this.agentProvider = agentProvider;
        this.suggestionService = suggestionService;
        this.validator = validator;
        this.cacheService = cacheService;
        this.budgetService = budgetService;
        this.userQuotaService = userQuotaService;
        this.metrics = metrics;
        this.providerProperties = providerProperties;
        this.agentProperties = agentProperties;
        this.sessionService = sessionService;
        this.ragProperties = ragProperties;
    }

    /**
     * 小悦统一入口：意图路由 → Agent 执行（自校验回喂重试 1 次）→ 异常降级直连
     *
     * @param request 对话请求（purpose 入口预传入；sessionId 可空走会话记忆）
     * @return 建议响应（degraded=true 时为失败提示，不落库）
     * @throws BizException purpose 非法（80001）、requestId 缺失（80002）、
     *                      用户配额超限（80003）、预算超限（80004）
     */
    public XiaoyueSuggestionResponse chat(XiaoyueChatRequest request) {
        // ① purpose 校验：入口预传入（P6），仅支持生成类用途；缺省/未知/机审用途一律拒绝并告警
        AiPurposeEnum purpose = request == null ? null : request.getPurpose();
        if (purpose == null || !SUPPORTED_PURPOSES.contains(purpose.getCode())) {
            log.warn("小悦入口收到非法 purpose，拒绝 purpose={} requestId={}",
                    purpose == null ? null : purpose.getCode(),
                    request == null ? null : request.getRequestId());
            throw new BizException(ErrorCode.AI_PURPOSE_REQUIRED);
        }
        // ② requestId 必传（计量幂等键组成）；userId 必传（用户级配额归属）
        // 匿名问答放行（板块四 4.2/P5）：userId 为空且 purpose=QA 且携带 anonIpHash → 匿名配额校验后放行
        boolean anonymous = request.getUserId() == null;
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        if (anonymous) {
            // 匿名模式仅支持 QA：其他用途必须登录（计量归属与建议态确认都需要用户身份）
            if (purpose != AiPurposeEnum.QA) {
                throw new BizException(ErrorCode.PARAM_ERROR, "该任务需要登录后使用");
            }
            if (request.getAnonIpHash() == null || request.getAnonIpHash().isBlank()) {
                throw new BizException(ErrorCode.PARAM_ERROR, "匿名问答必须携带 anonIpHash");
            }
            // 匿名配额（P5：IP 哈希×日 Redis 计数，超额拒绝 80003）
            if (!userQuotaService.tryAcquireAnonymous(request.getAnonIpHash(), ragProperties.getAnonQaDailyLimit())) {
                throw new BizException(ErrorCode.AI_QUOTA_EXCEEDED);
            }
        }
        // ③ 关联文章校验：摘要/标签/结构分析必须关联文章；问答与润色可不关联（润色对象可为选中文本）
        if (purpose != AiPurposeEnum.QA && purpose != AiPurposeEnum.POLISH && request.getArticleId() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该任务必须携带 articleId");
        }
        // ④ 用户级配额（D5/V8：按 用户×用途×日 校验，超额拒绝；匿名模式已走匿名配额跳过）
        if (!anonymous && !userQuotaService.tryAcquire(request.getUserId(), purpose)) {
            throw new BizException(ErrorCode.AI_QUOTA_EXCEEDED);
        }
        // ⑤ 预算分账判定（生成类 EXCEEDED 停新请求，明确错误码；Agent/降级链路均被覆盖）
        AiBudgetDecision decision = budgetService.check(purpose);
        if (decision == AiBudgetDecision.EXCEEDED) {
            log.warn("小悦入口预算超限拒绝 purpose={} requestId={}", purpose.getCode(), request.getRequestId());
            metrics.recordQuotaExceeded(purpose.getCode());
            throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED);
        } else if (decision == AiBudgetDecision.WARN) {
            // ≥80%：只提醒不停用
            log.warn("小悦入口预算预警（≥80%）purpose={} requestId={}", purpose.getCode(), request.getRequestId());
        }
        // ⑥ 建议缓存回查（P7：命中直接返回，幂等重试不重复计量不重复调用模型）
        var cached = cacheService.get(request.getRequestId());
        if (cached.isPresent()) {
            log.info("小悦入口建议缓存命中 requestId={} purpose={}", request.getRequestId(), purpose.getCode());
            return cached.get();
        }
        // ⑦ 会话准备（板块三 3.1）：sessionId 非空走会话记忆——归属校验 + USER 消息落库 + 活跃刷新
        AiSessionService.SessionBound bound = prepareSession(request, purpose);
        // ⑧ 开启调用上下文（Agent 每轮计量与轮次上限依赖它）
        AgentCallContext context = AgentCallContext.begin(
                request.getRequestId(), purpose, request.getUserId(), request.getScene());
        // ⑨ RAG 引用上下文（板块四 4.2）：本轮检索命中写入，终答组装时 drain 回传引用卡片
        com.litisle.litisleai.rag.RagContext.begin();
        try {
            return executeAgent(request, purpose, context, bound);
        } finally {
            // 上下文必须清理，防线程池复用串号
            AgentCallContext.clear();
            // RAG 引用上下文兜底清理（未 drain 的残留丢弃）
            com.litisle.litisleai.rag.RagContext.drain();
        }
    }

    /**
     * 会话准备（板块三 3.1/3.2）：sessionId 非空 → 归属校验 + USER 消息落库 + 活跃刷新；
     * sessionId 为空 → 一次性请求（memoryId 用 requestId 一次性键，不落库）
     *
     * @param request 对话请求
     * @param purpose 用途
     * @return 会话绑定信息（memoryId 与会话 ID；session 为 null 表示一次性请求）
     */
    private AiSessionService.SessionBound prepareSession(XiaoyueChatRequest request, AiPurposeEnum purpose) {
        // 无会话请求：memoryId 用一次性键（requestId 前缀），Redis TTL 30 分钟自然过期不跨请求
        if (request.getSessionId() == null) {
            return AiSessionService.SessionBound.of("tmp:" + request.getRequestId(), null);
        }
        // 有会话请求：归属校验（不存在/跨用户/已删拒绝）+ USER 消息落库 + 活跃刷新
        sessionService.requireOwnedSession(request.getUserId(), request.getSessionId());
        // USER 消息内容：意图相关文本（问答/润色为用户消息；摘要/标签为任务说明占位）
        String userContent = request.getMessage() == null || request.getMessage().isBlank()
                ? purpose.getDesc() + "任务（文章 " + request.getArticleId() + "）" : request.getMessage();
        sessionService.appendMessage(request.getSessionId(), AiSessionService.ROLE_USER, userContent, 0, null, null);
        sessionService.touch(request.getSessionId());
        return AiSessionService.SessionBound.of(String.valueOf(request.getSessionId()), request.getSessionId());
    }

    /**
     * Agent 执行路径：意图指令 → Agent 循环 → 结构化校验（回喂重试 1 次）→ 异常降级直连
     */
    private XiaoyueSuggestionResponse executeAgent(XiaoyueChatRequest request, AiPurposeEnum purpose,
                                                   AgentCallContext context, AiSessionService.SessionBound bound) {
        // 通道未启用（enabled=false）：Agent Bean 缺席，抛明确错误码（不静默）
        XiaoyueAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "AI 模型通道未启用（litisle.ai.provider.text.enabled=false）");
        }
        // 组装意图指令（prompt 模板集中管理；意图按 purpose 预传入，不做路由 LLM 调用）
        String intentMessage = buildIntentMessage(request, purpose);
        try {
            // Agent 推理：自主调工具（取稿/查池/预检/检索占位），每轮计量由 MeteringAgentChatModel 收口；
            // memoryId 区分会话记忆（sessionId）与一次性上下文（tmp:requestId）
            String raw = agent.chat(bound.memoryId(), intentMessage);
            return finalizeAgentOutput(request, purpose, context, bound, intentMessage, raw, 1);
        } catch (BizException e) {
            // 降级铁律：循环超限/模型不可用 → 降级为固定管道直连（降级链路同样计量）
            if (e.getCode() == ErrorCode.AI_AGENT_LOOP_EXCEEDED.getCode()
                    || e.getCode() == ErrorCode.AI_PROVIDER_UNAVAILABLE.getCode()) {
                log.warn("Agent 执行异常触发降级 purpose={} requestId={} code={} reason={}",
                        purpose.getCode(), request.getRequestId(), e.getCode(), e.getMessage());
                return fallbackToDirectPipe(request, purpose, context, bound);
            }
            // 其余业务异常（参数/预算类）原样上抛
            throw e;
        }
    }

    /**
     * Agent 输出结构化校验收口：通过即返回；不过则回喂重试 1 次；仍不过降级失败提示
     *
     * @param attempt 已消耗的 Agent 调用次数（仅日志观测用）
     */
    private XiaoyueSuggestionResponse finalizeAgentOutput(XiaoyueChatRequest request, AiPurposeEnum purpose,
                                                          AgentCallContext context, AiSessionService.SessionBound bound,
                                                          String intentMessage,
                                                          String raw, int attempt) {
        try {
            SuggestionValidator.ValidatedSuggestion suggestion = validator.validate(purpose, raw);
            // 校验通过：组装成功响应并写建议缓存（P7）
            XiaoyueSuggestionResponse response = buildSuccessResponse(context, purpose, suggestion);
            // 引用卡片回传（板块四 4.2：drain 本轮检索命中；QA 场景才有内容）
            response.setCitations(toCitationDtos(com.litisle.litisleai.rag.RagContext.drain()));
            cacheService.put(request.getRequestId(), response);
            // 会话模式：终答 ASSISTANT 消息落库（轮次 token 记于终答行，与 ai_token_usage 对账）+ 活跃刷新
            saveAssistantReply(bound, raw);
            return response;
        } catch (IllegalArgumentException e) {
            // 校验不过：把具体错误信息回喂模型重试 1 次（重试预算固定，防反思循环成本放大）
            if (attempt > 1) {
                // 已是回喂轮仍失败：降级为失败提示，不落库（2.4 失败语义）
                log.warn("Agent 输出自校验重试后仍失败 requestId={} purpose={} error={}",
                        request.getRequestId(), purpose.getCode(), e.getMessage());
                return buildDegradedResponse(context, purpose);
            }
            log.info("Agent 输出校验未通过，回喂重试 requestId={} purpose={} error={}",
                    request.getRequestId(), purpose.getCode(), e.getMessage());
            try {
                XiaoyueAgent agent = agentProvider.getIfAvailable();
                // 回喂轮（callSeq 由计量装饰模型递增；Agent Bean 缺席走降级）
                String retryRaw = agent == null ? null : agent.chat(bound.memoryId(), String.format(
                        AiPromptConstants.STRUCTURED_RETRY_FEEDBACK, e.getMessage(), intentMessage));
                if (retryRaw == null) {
                    return fallbackToDirectPipe(request, purpose, context, bound);
                }
                return finalizeAgentOutput(request, purpose, context, bound, intentMessage, retryRaw, attempt + 1);
            } catch (BizException ex) {
                // 回喂轮通道异常：同样降级为直连管道（降级链路同样计量）
                if (ex.getCode() == ErrorCode.AI_AGENT_LOOP_EXCEEDED.getCode()
                        || ex.getCode() == ErrorCode.AI_PROVIDER_UNAVAILABLE.getCode()) {
                    return fallbackToDirectPipe(request, purpose, context, bound);
                }
                throw ex;
            }
        }
    }

    /**
     * 终答消息落库（仅会话模式；DB 全量存档与 Redis 窗口暂存两层口径，落库失败不影响建议返回）
     *
     * @param bound 会话绑定（session 为 null 表示一次性请求，跳过落库）
     * @param reply 模型终答原文（结构化 JSON 或自由文本）
     */
    private void saveAssistantReply(AiSessionService.SessionBound bound, String reply) {
        if (bound.sessionId() == null || reply == null || reply.isBlank()) {
            return;
        }
        try {
            // 该轮 token 消耗记于终答行（AgentCallContext 最近一轮记录），与 ai_token_usage 日汇总对账
            sessionService.appendMessage(bound.sessionId(), AiSessionService.ROLE_ASSISTANT, reply,
                    0, contextLastTokenIn(), contextLastTokenOut());
        } catch (Exception e) {
            // 消息存档失败不阻断建议返回（列表展示降级，计量明细不受影响）
            log.warn("终答消息落库失败 sessionId={}", bound.sessionId(), e);
        }
    }

    /**
     * 读取上下文最近一轮输入 token（无上下文返回 null）
     */
    private Integer contextLastTokenIn() {
        AgentCallContext context = AgentCallContext.get();
        return context == null ? null : context.getLastTokenIn();
    }

    /**
     * 读取上下文最近一轮输出 token（无上下文返回 null）
     */
    private Integer contextLastTokenOut() {
        AgentCallContext context = AgentCallContext.get();
        return context == null ? null : context.getLastTokenOut();
    }

    /**
     * RAG 引用上下文 → 响应引用卡片 DTO（板块四 4.2：仅元数据不放正文原文）
     *
     * @param citations 引用上下文记录
     * @return 引用卡片列表；空时返回 null（不返回字段噪音）
     */
    private java.util.List<com.litisle.litisleapi.dto.ai.XiaoyueCitationDTO> toCitationDtos(
            java.util.List<com.litisle.litisleai.rag.RagContext.Citation> citations) {
        if (citations == null || citations.isEmpty()) {
            return null;
        }
        return citations.stream()
                .map(c -> new com.litisle.litisleapi.dto.ai.XiaoyueCitationDTO(
                        c.articleId(), c.title(), c.contentHash(), c.removed()))
                .toList();
    }

    /**
     * 降级直连：解除循环约束后按用途转固定管道（callSeq 连续累计，明细 degraded=1）
     */
    private XiaoyueSuggestionResponse fallbackToDirectPipe(XiaoyueChatRequest request, AiPurposeEnum purpose,
                                                           AgentCallContext context, AiSessionService.SessionBound bound) {
        // 解除 Agent 循环上限约束（降级管道直连，轮次计数只用于计量序号）
        context.leaveAgentPhase();
        return switch (purpose) {
            case SUMMARY -> suggestionService.summaryFallback(request, context);
            case TAGGING -> suggestionService.tagFallback(request, context);
            case QA -> suggestionService.qaFallback(request, context);
            case POLISH -> suggestionService.polishFallback(request, context);
            case STRUCTURE -> suggestionService.structureFallback(request, context);
            default -> buildDegradedResponse(context, purpose);
        };
    }

    /**
     * 按 purpose 组装意图指令（摘要/标签/结构分析必须关联文章已在入口校验；问答/润色可无文章）
     */
    private String buildIntentMessage(XiaoyueChatRequest request, AiPurposeEnum purpose) {
        return switch (purpose) {
            case SUMMARY -> String.format(AiPromptConstants.INTENT_SUMMARY,
                    request.getArticleId(), agentProperties.getSummaryMaxChars());
            case TAGGING -> String.format(AiPromptConstants.INTENT_TAGGING,
                    request.getArticleId(), agentProperties.getMaxTagCount());
            case QA -> String.format(AiPromptConstants.INTENT_QA,
                    request.getMessage() == null || request.getMessage().isBlank()
                            ? "请介绍这篇文章的核心内容" : request.getMessage(),
                    request.getArticleId() == null ? "无" : request.getArticleId());
            case POLISH -> String.format(AiPromptConstants.INTENT_POLISH,
                    request.getArticleId() == null ? "无" : request.getArticleId(),
                    request.getMessage() == null ? "" : request.getMessage(), "");
            case STRUCTURE -> String.format(AiPromptConstants.INTENT_STRUCTURE,
                    request.getArticleId() == null ? "无" : request.getArticleId(),
                    request.getMessage() == null ? "" : request.getMessage(), "");
            default -> throw new BizException(ErrorCode.AI_PURPOSE_REQUIRED);
        };
    }

    /**
     * 组装成功响应（字段按用途填充；D3/D13：带 AI 生成标识）
     */
    private XiaoyueSuggestionResponse buildSuccessResponse(AgentCallContext context, AiPurposeEnum purpose,
                                                           SuggestionValidator.ValidatedSuggestion suggestion) {
        XiaoyueSuggestionResponse response = XiaoyueSuggestionResponse.builder()
                .requestId(context.getRequestId())
                .purpose(purpose.getCode())
                .aiGenerated(true)
                .degraded(false)
                .message(AI_NOTICE_MESSAGE)
                .modelVersion(providerProperties.getModel())
                .build();
        switch (purpose) {
            case SUMMARY -> response.setSummary(suggestion.getSummary());
            case TAGGING -> response.setTags(suggestion.getTags());
            case QA -> response.setAnswer(suggestion.getAnswer());
            case POLISH -> response.setPolished(suggestion.getPolished());
            case STRUCTURE -> response.setAnalysis(suggestion.getAnalysis());
            default -> throw new BizException(ErrorCode.AI_PURPOSE_REQUIRED);
        }
        return response;
    }

    /**
     * 构建降级失败响应（D4：不落库；可手动填写）
     */
    private XiaoyueSuggestionResponse buildDegradedResponse(AgentCallContext context, AiPurposeEnum purpose) {
        return XiaoyueSuggestionResponse.builder()
                .requestId(context.getRequestId())
                .purpose(purpose.getCode())
                .aiGenerated(false)
                .degraded(true)
                .message(DEGRADED_MESSAGE)
                .modelVersion(providerProperties.getModel())
                .build();
    }
}
