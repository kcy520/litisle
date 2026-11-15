package com.litisle.litisleai.controller;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.dto.XiaoyueChatRequest;
import com.litisle.litisleai.dto.XiaoyueSuggestRequest;
import com.litisle.litisleai.dto.XiaoyueSuggestionResponse;
import com.litisle.litisleai.service.XiaoyueChatService;
import com.litisle.litisleai.service.XiaoyueSuggestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小悦 REST 接入层（板块二 2.3/2.5 统一入口与直连端点 + 板块三 3.3 润色/结构分析，
 * 路径口径 AI-Architecture §7.1）
 *
 * <p>【职责边界】controller 只做协议转换与统一响应包装；purpose 覆写、配额/预算判定、
 * 意图路由、降级直连、结构化自校验、会话记忆、计量留痕全部收口在 service 层。</p>
 *
 * <p>【网关口径】/ai/** 业务前缀按业务指南由网关以 /api/ai/** 暴露（落地前须在
 * Api-Architecture 定稿登记）；端点响应为建议态（D4 不自动落库）。</p>
 *
 * @author litisle
 */
@RestController
@RequestMapping("/ai/xiaoyue")
@RequiredArgsConstructor
public class XiaoyueController {

    /** 统一入口编排服务（意图路由 + 降级直连 + 会话记忆） */
    private final XiaoyueChatService chatService;

    /** 直连管道服务（固定管道摘要/标签/润色/结构分析建议） */
    private final XiaoyueSuggestionService suggestionService;

    /**
     * 小悦 Agent 统一入口（2.3：purpose 入口预传入，Agent 意图路由 + 异常降级直连；
     * 板块三：sessionId 非空走多轮会话记忆）
     *
     * @param request 对话请求（SUMMARY/TAGGING/QA/POLISH/STRUCTURE）
     * @return 建议响应（degraded=true 为失败提示，可手动填写，不落库）
     */
    @PostMapping("/chat")
    public Result<XiaoyueSuggestionResponse> chat(@RequestBody XiaoyueChatRequest request) {
        return Result.success(chatService.chat(request));
    }

    /**
     * 摘要建议直连端点（2.5：固定管道，一次性动作不建会话；Agent 循环异常时的降级通道）
     *
     * @param request 直连请求（userId/requestId/articleId 必传）
     * @return 摘要建议（≤500 字，service 侧校验；建议态不自动落库）
     */
    @PostMapping("/summary")
    public Result<XiaoyueSuggestionResponse> summary(@RequestBody XiaoyueSuggestRequest request) {
        return Result.success(suggestionService.summaryDirect(request));
    }

    /**
     * 标签建议直连端点（2.5：固定管道；AI 不新造标签，越池校验在 service 侧）
     *
     * @param request 直连请求（userId/requestId/articleId 必传）
     * @return 标签建议（全部池内；建议态不自动落库）
     */
    @PostMapping("/tag")
    public Result<XiaoyueSuggestionResponse> tag(@RequestBody XiaoyueSuggestRequest request) {
        return Result.success(suggestionService.tagDirect(request));
    }

    /**
     * 润色建议端点（板块三 3.3：purpose 定死 POLISH；sessionId 非空走会话多轮迭代润色；
     * 固定直连通道，同时作为 2.3 Agent 循环异常的降级通道）
     *
     * @param request 对话请求（userId/requestId 必传；articleId/message 至少一项）
     * @return 润色建议（建议态，作者确认后才替换，不自动落库）
     */
    @PostMapping("/polish")
    public Result<XiaoyueSuggestionResponse> polish(@RequestBody XiaoyueChatRequest request) {
        // P6 口径：purpose 由功能入口预传入——本端点即功能入口，定死覆写防客户端误传
        if (request != null) {
            request.setPurpose(com.litisle.litisleapi.dto.ai.AiPurposeEnum.POLISH);
        }
        return Result.success(chatService.chat(request));
    }

    /**
     * 结构分析建议端点（板块三 3.3：purpose 定死 STRUCTURE；基于全文出结构问题清单与改法，
     * 不动手改写正文；同样作为 2.3 的降级通道）
     *
     * @param request 对话请求（userId/requestId/articleId 必传；message 可作补充要求）
     * @return 结构分析建议（建议态，不自动落库）
     */
    @PostMapping("/analyze-structure")
    public Result<XiaoyueSuggestionResponse> analyzeStructure(@RequestBody XiaoyueChatRequest request) {
        // P6 口径：purpose 由功能入口预传入——本端点即功能入口，定死覆写防客户端误传
        if (request != null) {
            request.setPurpose(com.litisle.litisleapi.dto.ai.AiPurposeEnum.STRUCTURE);
        }
        return Result.success(chatService.chat(request));
    }
}
