package com.litisle.litisleai.service;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.agent.AgentCallContext;
import com.litisle.litisleai.agent.MeteringAgentChatModel;
import com.litisle.litisleai.agent.XiaoyueAgent;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.constant.AiContentConstant;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.dto.XiaoyueChatRequest;
import com.litisle.litisleai.dto.XiaoyueSuggestionResponse;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.enums.AiMessageRole;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.rag.RagContext;
import com.litisle.litisleai.service.support.SuggestionValidator;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import com.litisle.litisleapi.dto.ai.XiaoyueCitationDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * 小悦统一入口编排服务（板块二 2.3：POST /ai/xiaoyue/chat 意图路由 + 降级直连；板块三 3.3 接入会话记忆与润色/结构分析）
 *
 * 
 *  * ## XiaoyueChatService 是统一入口吗？
是「Agent 链路的统一入口」，但不是所有请求的入口 。它收口的是`/ai/xiaoyue/chat` 、`/polish` 、`/analyze-structure` 三个端点（ XiaoyueController.java#L72-L129 ），统一做九道关卡（purpose 校验 → 匿名规则 → 配额 → 预算 → 缓存 → 会话 → 上下文 → Agent）。但`/summary` 、`/tag` 直连端点 不经过它 ，直接走 XiaoyueSuggestionService 的`summaryDirect` /`tagDirect` （配额预算判定在直连入口自己重复一遍）。
 * 


 * <p>【意图路由口径】P6 定档：purpose 由功能入口预传入，Agent 循环继承，不做路由 LLM 调用；
 * 本服务按 purpose 组装意图指令后交 Agent 执行（Agent 自主决定调哪个工具、调几次）。</p>
 *
 * <p>【会话口径（板块三 3.1/3.2）】sessionId 非空：归属校验 → USER 消息落库（ai_message 全量存档）
 * → Agent 以 sessionId 为 memoryId 走 Redis 窗口记忆 → 终答 ASSISTANT 落库（带轮次 token 对账）；
 * 
 * sessionId 为空：一次性请求（memoryId=一次性 requestId 键，不落 ai_session/ai_message）。</p>
 *
 * ## 即使是多轮会话，落库的也只有两条消息
关键区别在于 Agent 的中间轮不落库 ：

## 即使是多轮会话，落库的也只有两条消息
关键区别在于 Agent 的中间轮不落库 ：
- 落库 ：用户原话（USER， prepareSession 里 appendMessage ）+ 最终回复（ASSISTANT， L387 且带该轮 token 数与`ai_token_usage` 对账）
- 不落库 ：Agent 循环内部的工具回喂轮、自校验重试轮（如 L353 ）——这些中间消息只活在 Redis 窗口记忆（`MessageWindowChatMemory` 最近 N 条，30 分钟 TTL），过期即弃
 * 分支	        条件	                                 落库情况
 * 多轮会话     	sessionId 非空（用户开了聊天框）	        落库：USER 消息 + 终答 ASSISTANT
 * 一次性请求	sessionId 为空（工具栏点摘要/标签等）	    不落库：ai_session/ai_message 一条不写
 *
 * 一句话： DB 只存「用户问了什么、AI 最终答了什么」这一头一尾；Agent 中间怎么调工具、怎么试错，只在 Redis 短暂留痕 。计量侧则相反——每一轮（含中间轮）都落`ai_token_usage` 明细，这是成本对账需要的。
 * 
 * 
 * <p>【降级铁律】Agent 循环超限（80007）/模型不可用（80005）→ 解除循环约束（callSeq 连续累计）
 * → 降级为固定管道直连（XiaoyueSuggestionService，降级链路同样计量，明细 degraded=1）；
 * 降级也失败 → 返回「暂时无法生成」降级响应（可手动填写，不落库），不抛异常。</p>
 *
 * 【链路决策分层】生成链路的决策权分三层，本类持有第①③层：
 * ①走 Agent 还是直连——由前端调用的端点决定（入口即路由：/chat、/polish、/analyze-structure
 * 一律先走 Agent 链路；/summary、/tag 直连端点不经本类，直接走 XiaoyueSuggestionService）；
 * ②Agent 循环内调哪个工具、调几次——模型在 AiServices 循环内自主决定（返回文本答案=循环结束，
 * 返回工具请求=框架执行回喂续轮），轮次上限由 {@link com.litisle.litisleai.agent.MeteringAgentChatModel} 强制；
 * ③Agent 失败是否降级直连——由本类代码决定（捕获 80007/80005 或自校验重试耗尽，见【降级铁律】），
 * Agent 自身无降级自主权，不会主动判定「我处理不了」。</p>
 *
 * <p>【结构化自校验（2.4）】SUMMARY/TAGGING/POLISH/STRUCTURE 要求 JSON 输出：解析失败或校验
 * 不过把具体错误回喂模型重试 1 次；仍失败降级为失败提示，不落库。校验规则在 service 侧执行。</p>
 *
 * <p>【计量承诺】配额与预算在路由后即判定（Agent/降级链路均被覆盖）；Agent 每轮计量由
 * {@link MeteringAgentChatModel} 收口，降级管道计量在 XiaoyueSuggestionService 收口；
 * 同一 requestId 全链路只扣一次（配额判定式读取日汇总，计量明细按轮落库）。</p>
 *
 * <p>【通俗说明】小悦 AI 助手（摘要/打标/问答/润色/结构分析）的"总调度台"：
 * 用户请求进来后先过一道道关卡——用途合法吗、登录了吗（匿名只能问答且限次）、
 * 关联文章了吗、个人配额和平台预算还有吗、缓存里有没有现成答案——全过关才开干。
 * 干活时优先交给 Agent（它能自己取稿、查标签池、做检索），产出不合格就回喂重试；
 * Agent 实在不行就降级为固定管道直连，再不行返回"可手动填写"的提示。会话模式下
 * 用户的提问和 AI 的回答都会存档，检索命中的来源文章也会作为"引用卡片"返回。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
public class XiaoyueChatService {


//    ## 什么是「终答 ASSISTANT」
//    聊天记录里每条消息都有个身份标签（role）：
//    ai_message 表（某次多轮会话的存档）
//            ┌──────┬────────────────────────────┬─────┐
//            │ role │ content                    │ ... │
//            ├──────┼────────────────────────────┼─────┤
//            │ USER │ 帮我总结这篇文章            │ ← 用户说的话
//│ ASSISTANT │ 本文讲了……（最终版）  │ ← 终答：AI 给用户的最后回复
//└──────┴────────────────────────────┴─────┘
//        「终答」= 这次请求最终返回给用户看的那条回复 。之所以强调「终」，是因为 AI 内部为产生它可能调了好几次模型（查文章工具、回喂重试等），但那些中间产物用户看不到、也不存档—— 只有最后敲定给用户的答案才叫终答 ，以`ASSISTANT` 角色落库。
//
//            ## 实现链路（通俗版）
//            1. 用户消息先入册 ：请求进来，`prepareSession` 先把用户的话以`USER` 角色写进`ai_message` （ XiaoyueChatService.java#L243 ）——此时 AI 还没开始干活
//            2. Agent 关起门干活 ：中间轮（调工具、试错）的消息只在 Redis 窗口记忆里倒腾，不碰数据库
//            3. 敲定终答才入册 ：拿到最终回复后， XiaoyueChatService.java#L386-L387 ：
//
//            // 该轮 token 消耗记于终答行（AgentCallContext 最近一轮记录），与 ai_token_usage 日汇总对账
//            sessionService.appendMessage(bound.sessionId(), AiMessageRole.ASSISTANT, reply, ...);
//
//    和 USER 那行的关键差别： 这行带 token 数 （`promptTokens` /`completionTokens` ，取自`AgentCallContext.recordLastTokens` 记的最后一轮消耗）。因为只有终答对应「这一轮对话真正花掉了多少 token」，把它记在 ASSISTANT 行上，前端展示「这条回答花了多少」、以及和计量明细表`ai_token_usage` 对账，都有据可查。USER 行的 token 数恒为 0（用户打字不消耗模型 token）


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
     *
     * <p>【通俗说明】把调度台需要的十来个协作组件装配进来（Agent 用 ObjectProvider
     * 按需取用，功能没开也能正常启动）。</p>
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
     * @param userId  当前登录用户 ID（controller 从 Sa-Token 会话取得；未登录为 null，仅 QA 允许匿名）
     * @return 建议响应（degraded=true 时为失败提示，不落库）
     * @throws BizException purpose 非法（80001）、requestId 缺失（80002）、
     *                      用户配额超限（80003）、预算超限（80004）
     *
     * <p>【通俗说明】九道关卡流水线：① purpose 合法性；② requestId 必传 + 匿名
     * 规则（只有问答可匿名、必须带 IP 哈希、限每日次数）；③ 生成类任务必须关联
     * 文章；④ 登录用户的配额；⑤ 平台预算；⑥ 缓存（命中直接返回不重复花钱）；
     * ⑦ 会话准备（落库用户消息）；⑧ 开调用上下文；⑨ 开引用上下文后交给 Agent
     * 执行（finally 里兜底清理两个上下文防线程串号）。</p>
     */
    public XiaoyueSuggestionResponse chat(XiaoyueChatRequest request, Long userId) {
        // ① purpose 校验：入口预传入（P6），仅支持生成类用途；缺省/未知/机审用途一律拒绝并告警
            //purpose = AI 能力的用途类型，由前端在调用时通过 request.purpose 传入，后端按它决定走哪条处理链路、调哪个 prompt、返回哪个字段。
        AiPurposeEnum purpose = request == null ? null : request.getPurpose();
        if (purpose == null || !SUPPORTED_PURPOSES.contains(purpose.getCode())) {
            log.warn("小悦入口收到非法 purpose，拒绝 purpose={} requestId={}",
                    purpose == null ? null : purpose.getCode(),
                    request == null ? null : request.getRequestId());
            throw new BizException(ErrorCode.AI_PURPOSE_REQUIRED);
        }
        // ② requestId 必传（计量幂等键组成）；userId 取自登录态（用户级配额归属）
        // 匿名问答放行（板块四 4.2/P5）：未登录且 purpose=QA 且携带 anonIpHash → 匿名配额校验后放行
        boolean anonymous = userId == null;
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        //如果匿名用户
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

        // ③ 关联校验：摘要/标签/结构分析必须关联文章（articleId 或板块九 articleSeq 序号引用
        // 二选一——读后摘要/大纲导航经会话结果集序号定位）；问答与润色可不关联
        if (purpose != AiPurposeEnum.QA && purpose != AiPurposeEnum.POLISH
                && request.getArticleId() == null && request.getArticleSeq() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该任务必须携带 articleId 或会话结果集序号 articleSeq");
        }
        // ④ 用户级配额（D5/V8：按 用户×用途×日 校验，超额拒绝；匿名模式已走匿名配额跳过）
        if (!anonymous && !userQuotaService.tryAcquire(userId, purpose)) {
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
        AiSessionService.SessionBound bound = prepareSession(request, purpose, userId);
        // ⑧ 开启调用上下文（Agent 每轮计量与轮次上限依赖它；会话 ID 供结果动作/序号解析工具定位，板块九 1.1）
        AgentCallContext context = AgentCallContext.begin(
                request.getRequestId(), purpose, userId, request.getScene(), bound.sessionId());
        // ⑨ RAG 引用上下文（板块四 4.2）：本轮检索命中写入，终答组装时 drain 回传引用卡片
        RagContext.begin();
        try {
            return executeAgent(request, purpose, context, bound);
        } finally {
            // 上下文必须清理，防线程池复用串号
            AgentCallContext.clear();
            // RAG 引用上下文兜底清理（未 drain 的残留丢弃）
            RagContext.drain();
        }
    }

    /**
     * 会话准备（板块三 3.1/3.2）：sessionId 非空 → 归属校验 + USER 消息落库 + 活跃刷新；
     * sessionId 为空 → 一次性请求（memoryId 用 requestId 一次性键，不落库）
     *
     * @param request 对话请求
     * @param purpose 用途
     * @param userId  当前登录用户 ID（会话归属校验依据；未登录为 null）
     * @return 会话绑定信息（memoryId 与会话 ID；session 为 null 表示一次性请求）
     *
     * <p>【通俗说明】决定"这轮对话挂在哪本记忆册"：没传 sessionId 就用一次性临时键
     * （30 分钟自动过期，不留底）；传了就验会话归属、把用户消息存进数据库、刷新
     * 活跃时间，然后以正式会话 ID 作为记忆钥匙。</p>
     */
    private AiSessionService.SessionBound prepareSession(XiaoyueChatRequest request, AiPurposeEnum purpose, Long userId) {

//① 30min 临时的？不存数据库？ 是。一次性请求的 Agent 执行也需要一个 memoryId（LangChain4j AiServices 强制要求），但这个记忆只在 本次请求内部 有意义——Agent 循环里工具回喂轮需要暂存中间消息。所以放在 Redis`tmp:{requestId}` ，TTL 30 分钟来自 AiAgentProperties.java#L72`memoryTtlMinutes = 30` ，过期即弃，`ai_session` /`ai_message` 一条不写（D4 建议态不落库同源口径）。
//② 30min 后如何改为存入？ 永远不会自动转正 。落不落库在请求进来那一刻就由`sessionId == null` 一锤定音，tmp → 正式会话没有升级路径。要持久化多轮，前端必须 先调 SessionController.java#L54 显式创建会话拿到 sessionId ，后续请求都带上它——从第一轮起就走`appendMessage` 落库。
//③ 每个会话的第一次都是临时会话？ 不是。多轮会话的第一条消息就已经带着 sessionId（前端创建会话后必然携带），第一轮就落库；一次性动作则 从头到尾都不建会话 。`tmp:` 键甚至不是给「跨请求记忆」用的——同一 requestId 30 分钟内重试会先命中建议缓存（P7），根本走不到记忆那步，30 分钟 TTL 只是给 Redis 垃圾兜底。
//一句话： `sessionId` 有无 = 「用户开了聊天会话」vs「点了一次工具按钮」，二者天生不同类，不存在临转会话的中间态 。

        // 无会话请求：memoryId 用一次性键（requestId 前缀），Redis TTL 30 分钟自然过期不跨请求
        if (request.getSessionId() == null) {
            return AiSessionService.SessionBound.of(AiContentConstant.tmpMemoryId(request.getRequestId()), null);
        }
        // 有会话请求：归属校验（不存在/跨用户/已删拒绝）+ USER 消息落库 + 活跃刷新
        sessionService.requireOwnedSession(userId, request.getSessionId());
        // USER 消息内容：意图相关文本（问答/润色为用户消息；摘要/标签为任务说明占位）
        sessionService.appendMessage(request.getSessionId(), AiMessageRole.USER,
                buildUserMessageContent(request, purpose), 0, null, null);
        sessionService.touch(request.getSessionId());

        return AiSessionService.SessionBound.of(String.valueOf(request.getSessionId()), request.getSessionId());
    }

    /**
     * 构建落库的 USER 消息内容（板块三 3.1：会话历史存档与回放对齐）
     *
     * <p>【口径】用户有输入（问答问题/润色指令/结构分析补充要求）存用户原话；
     * 无输入的定向任务（摘要/标签）存任务占位文案，标注目标文章便于历史可读。</p>
     *
     * @param request 对话请求
     * @param purpose 用途打标（占位文案取用途描述）
     * @return USER 消息内容（用户原话或任务占位文案）
     */
    private String buildUserMessageContent(XiaoyueChatRequest request, AiPurposeEnum purpose) {
        /* 业务逻辑步骤：
         * 1. 用户有输入 → 原样返回用户消息（保留原话便于会话回放）
         * 2. 用户无输入 → 返回任务占位文案（用途描述 + 关联文章 ID） */
        // 有用户输入优先存原文——问答/润色场景的用户指令是会话语义的一部分
        if (request.getMessage() != null && !request.getMessage().isBlank()) {
            return request.getMessage();
        }
        // 无用户输入（摘要/标签等定向任务）：占位文案标注目标文章，仅作历史展示不回喂模型
        return String.format(AiContentConstant.SESSION_TASK_MESSAGE_FORMAT,
                purpose.getDesc(), request.getArticleId());
    }

    /**
     * Agent 执行路径：意图指令 → Agent 循环 → 结构化校验（回喂重试 1 次）→ 异常降级直连
     *
     * <p>【通俗说明】按用途组装意图指令交给 Agent（Agent 自己决定调哪些工具、调几
     * 次）；循环超限或模型不可用就转固定管道降级，其他业务异常原样上抛。</p>
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
     *
     * <p>【通俗说明】
     *验收 Agent 的作业：合格就打包响应、回传引用卡片、写缓存、
     * 把回答存进会话；不合格把具体错误回喂让它重做一次，还不合格返回"可手动
     * 填写"的失败提示。</p>
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
            response.setCitations(toCitationDtos(RagContext.drain()));
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
                    //按用途把请求转给对应的固定管道降级方法（摘要/标签/问答/润色/ 结构分析各有一个），不支持的用途直接返回失败提示
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
     *
     * <p>【通俗说明】
     *     会话模式下把 AI 的回答存档（带本轮 token 数便于对账）；
     * 存档失败不影响建议返回，只是聊天列表里少了这条。</p>
     */
    private void saveAssistantReply(AiSessionService.SessionBound bound, String reply) {
        if (bound.sessionId() == null || reply == null || reply.isBlank()) {
            return;
        }
        try {
            // 该轮 token 消耗记于终答行（AgentCallContext 最近一轮记录），与 ai_token_usage 日汇总对账
            sessionService.appendMessage(bound.sessionId(), AiMessageRole.ASSISTANT, reply,
                    0, contextLastTokenIn(), contextLastTokenOut());
        } catch (Exception e) {
            // 消息存档失败不阻断建议返回（列表展示降级，计量明细不受影响）
            log.warn("终答消息落库失败 sessionId={}", bound.sessionId(), e);
        }
    }

    /**
     * 读取上下文最近一轮输入 token（无上下文返回 null）
     *
     * <p>【通俗说明】从调用上下文取最近一轮的输入 token 数，供终答落库对账。</p>
     */
    private Integer contextLastTokenIn() {
        AgentCallContext context = AgentCallContext.get();
        return context == null ? null : context.getLastTokenIn();
    }

    /**
     * 读取上下文最近一轮输出 token（无上下文返回 null）
     *
     * <p>【通俗说明】从调用上下文取最近一轮的输出 token 数，供终答落库对账。</p>
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
     *
     * <p>【通俗说明】把本轮检索命中的引用（文章 ID/标题/哈希/是否已删）转成返回给
     * 前端的引用卡片；没有引用就不给这个字段。</p>
     */
    private List<XiaoyueCitationDTO> toCitationDtos(
            List<RagContext.Citation> citations) {
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
     *
     * <p>【通俗说明】按用途把请求转给对应的固定管道降级方法（摘要/标签/问答/润色/
     * 结构分析各有一个），不支持的用途直接返回失败提示。</p>
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
     * 按 purpose 组装意图指令（摘要/标签/结构分析必须关联文章已在入口校验；问答/润色可无文章）。
     * 板块九路由扩展：articleId 缺省而携带 articleSeq（会话结果集序号引用）时，摘要走读后摘要、
     * 结构走大纲导航、问答走读后追问——序号定位失败由工具观察文本明确回喂，不猜不编造
     *
     * <p>【通俗说明】给 Agent 下达的"任务卡"：按用途选对应的指令模板填好参数。
     * 摘要/结构分析/问答在用户用"第 N 篇"指代文章时，会切换到对应的"读后"版指令；
     * 问答还会按场景细分（读者预设问题/选题灵感/普通追问）。</p>
     */
    private String buildIntentMessage(XiaoyueChatRequest request, AiPurposeEnum purpose) {
        return switch (purpose) {
            // 读后摘要（板块九 1.3）：仅序号引用（articleId 缺省）走读后摘要指令；articleId 优先走原摘要链路
            case SUMMARY -> request.getArticleId() == null
                    ? String.format(AiPromptConstants.INTENT_POST_READ_SUMMARY, // 读后摘要模板：填结果集序号 + 摘要字数上限
                            request.getArticleSeq(), agentProperties.getSummaryMaxChars())
                    : String.format(AiPromptConstants.INTENT_SUMMARY, // 原摘要模板：填文章 ID + 摘要字数上限
                            request.getArticleId(), agentProperties.getSummaryMaxChars());
            case TAGGING -> String.format(AiPromptConstants.INTENT_TAGGING, // 标签模板：填文章 ID + 标签数量上限
                    request.getArticleId(), agentProperties.getMaxTagCount());
            // 读后追问（板块九 1.4/1.6/1.7/1.8）：关联了单文（articleId 或序号）走单文会话指令；
            // 板块十二创作辅助场景路由：读者预设问题（必须关联文章）/选题灵感（热榜上下文工具）
            case QA -> {
                // 场景路由①：读者预设问题——强制关联文章（无文章无法围绕文章提问）
                if (AiContentConstant.SCENE_READER_QUESTIONS.equals(request.getScene())) {
                    if (request.getArticleId() == null) {
                        throw new BizException(ErrorCode.PARAM_ERROR, "读者预设问题必须携带 articleId");
                    }
                    yield String.format(AiPromptConstants.INTENT_READER_QUESTIONS, // 预设问题模板：填问题数量 + 文章 ID + 问题数量
                            agentProperties.getReaderQuestionCount(), request.getArticleId(),
                            agentProperties.getReaderQuestionCount());
                }
                // 场景路由②：选题灵感——空消息兜底为默认灵感指令，用户输入则作为灵感方向
                if (AiContentConstant.SCENE_TOPIC_INSPIRATION.equals(request.getScene())) {
                    yield String.format(AiPromptConstants.INTENT_TOPIC_INSPIRATION,
                            request.getMessage() == null || request.getMessage().isBlank()
                                    ? "结合当前热榜给我一些选题灵感" : request.getMessage());
                }
                // 默认路由：未关联文章走通用问答（文章占位"无"）；关联了文章走读后追问（序号引用由 articleReference 描述）
                yield request.getArticleId() == null && request.getArticleSeq() == null
                        ? String.format(AiPromptConstants.INTENT_QA,
                                request.getMessage() == null || request.getMessage().isBlank()
                                        ? "请介绍这篇文章的核心内容" : request.getMessage(), // 空消息兜底默认介绍指令
                                "无") // 未关联文章的占位文本
                        : String.format(AiPromptConstants.INTENT_POST_READ_QA,
                                articleReference(request), request.getMessage() == null ? "" : request.getMessage()); // 空消息存空串由模板兜底
            }
            case POLISH -> String.format(AiPromptConstants.INTENT_POLISH, // 润色模板：文章可空占位"无"，选中文本为润色对象
                    request.getArticleId() == null ? "无" : request.getArticleId(),
                    request.getMessage() == null ? "" : request.getMessage(), ""); // 尾参空串：上下文约束暂不传
            // 大纲导航（板块九 1.5）：仅序号引用走大纲导航指令；articleId 走创作侧结构分析
            case STRUCTURE -> request.getArticleId() == null
                    ? String.format(AiPromptConstants.INTENT_OUTLINE, request.getArticleSeq()) // 大纲导航模板：填结果集序号
                    : String.format(AiPromptConstants.INTENT_STRUCTURE, // 结构分析模板：填文章 ID + 补充要求 + 尾参空串
                            request.getArticleId(),
                            request.getMessage() == null ? "" : request.getMessage(), ""); // 尾参空串：上下文约束暂不传
            default -> throw new BizException(ErrorCode.AI_PURPOSE_REQUIRED); // 机审等不支持用途已在入口拦截，此处兜底
        };
    }

    /**
     * 组装单文关联描述（articleId 优先；仅序号引用时描述为序号，由工具解析定位）
     *
     * @param request 对话请求
     * @return 文章关联描述文本（articleId 或「结果集序号 N」；均无返回「无」）
     *
     * <p>【通俗说明】拼"文章关联描述"：有 ID 写 ID，只有序号写"结果集序号 N"，
     * 都没有就写"无"。</p>
     */
    private String articleReference(XiaoyueChatRequest request) {
        if (request.getArticleId() != null) {
            return String.valueOf(request.getArticleId());
        }
        return request.getArticleSeq() == null ? "无" : "结果集序号 " + request.getArticleSeq();
    }

    /**
     * 组装成功响应（字段按用途填充；D3/D13：带 AI 生成标识）
     *
     * <p>【通俗说明】按用途把校验通过的内容装进响应对应字段（摘要/标签/回答/润色/
     * 结构分析各归各的字段），并带上 AI 生成标识。</p>
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
     *
     * <p>【通俗说明】失败提示响应：aiGenerated=false、degraded=true，前端提示用户
     * 手动填写。</p>
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
