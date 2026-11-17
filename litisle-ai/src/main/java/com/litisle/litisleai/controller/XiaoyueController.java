package com.litisle.litisleai.controller;

import com.litisle.commoncore.resolver.UserIdResolver;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.dto.DuplicateCheckRequest;
import com.litisle.litisleai.dto.DraftRequest;
import com.litisle.litisleai.dto.RewriteRequest;
import com.litisle.litisleai.dto.XiaoyueChatRequest;
import com.litisle.litisleai.dto.XiaoyueSimilarRequest;
import com.litisle.litisleai.dto.XiaoyueSimilarResponse;
import com.litisle.litisleai.dto.XiaoyueSuggestRequest;
import com.litisle.litisleai.dto.XiaoyueSuggestionResponse;
import com.litisle.litisleai.service.DuplicateCheckService;
import com.litisle.litisleai.service.SimilarRecommendService;
import com.litisle.litisleai.service.XiaoyueChatService;
import com.litisle.litisleai.service.XiaoyueSuggestionService;
import com.litisle.litisleai.service.WritingAssistService;
import com.litisle.litisleai.vo.DuplicateCheckResponse;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
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
 * <p>【身份口径】userId 属于登录态数据，禁止客户端传入：本层通过 common 的
 * {@link UserIdResolver}（Sa-Token 实现）取当前登录用户后作为独立入参交 service；
 * 未登录返回 null，仅问答与相似推荐允许匿名（匿名配额/空计量归属由 service 收口）。</p>
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

    /** 相似推荐编排服务（板块五 5.1/5.2：F8 相似文章推荐） */
    private final SimilarRecommendService similarRecommendService;

    /** 发布前查重服务（板块十二 4.2：查重编排 + 计量收口） */
    private final DuplicateCheckService duplicateCheckService;

    /** 写作辅助扩展编排服务（板块十五：多级改写建议 + 文章草稿生成） */
    private final WritingAssistService writingAssistService;

    /** 当前用户解析器（common-security 提供 Sa-Token 实现：未登录返回 null） */
    private final UserIdResolver userIdResolver;

    /**
     * 小悦 Agent 统一入口（2.3：purpose 入口预传入，Agent 意图路由 + 异常降级直连；
     * 板块三：sessionId 非空走多轮会话记忆）
     *
     * @param request 对话请求（SUMMARY/TAGGING/QA/POLISH/STRUCTURE）
     * @return 建议响应（degraded=true 为失败提示，可手动填写，不落库）
     */
    @PostMapping("/chat")
    public Result<XiaoyueSuggestionResponse> chat(@RequestBody XiaoyueChatRequest request) {
        return Result.success(chatService.chat(request, userIdResolver.getUserId()));
    }

    /**
     * 摘要建议直连端点（2.5：固定管道，一次性动作不建会话；Agent 循环异常时的降级通道）
     *
     * @param request 直连请求（requestId/articleId 必传；用户身份取自登录态）
     * @return 摘要建议（≤500 字，service 侧校验；建议态不自动落库）
     */
    @PostMapping("/summary")
    public Result<XiaoyueSuggestionResponse> summary(@RequestBody XiaoyueSuggestRequest request) {
        return Result.success(suggestionService.summaryDirect(request, userIdResolver.getUserId()));
    }

    /**
     * 标签建议直连端点（2.5：固定管道；AI 不新造标签，越池校验在 service 侧）
     *
     * @param request 直连请求（requestId/articleId 必传；用户身份取自登录态）
     * @return 标签建议（全部池内；建议态不自动落库）
     */
    @PostMapping("/tag")
    public Result<XiaoyueSuggestionResponse> tag(@RequestBody XiaoyueSuggestRequest request) {
        return Result.success(suggestionService.tagDirect(request, userIdResolver.getUserId()));
    }

    /**
     * 润色建议端点（板块三 3.3：purpose 定死 POLISH；sessionId 非空走会话多轮迭代润色；
     * 固定直连通道，同时作为 2.3 Agent 循环异常的降级通道）
     *
     * @param request 对话请求（requestId 必传；articleId/message 至少一项；用户身份取自登录态）
     * @return 润色建议（建议态，作者确认后才替换，不自动落库）
     */
    @PostMapping("/polish")
    public Result<XiaoyueSuggestionResponse> polish(@RequestBody XiaoyueChatRequest request) {
        // P6 口径：purpose 由功能入口预传入——本端点即功能入口，定死覆写防客户端误传
        if (request != null) {
            request.setPurpose(AiPurposeEnum.POLISH);
        }
        return Result.success(chatService.chat(request, userIdResolver.getUserId()));
    }

    /**
     * 结构分析建议端点（板块三 3.3：purpose 定死 STRUCTURE；基于全文出结构问题清单与改法，
     * 不动手改写正文；同样作为 2.3 的降级通道）
     *
     * @param request 对话请求（requestId/articleId 必传；message 可作补充要求；用户身份取自登录态）
     * @return 结构分析建议（建议态，不自动落库）
     */
    @PostMapping("/analyze-structure")
    public Result<XiaoyueSuggestionResponse> analyzeStructure(@RequestBody XiaoyueChatRequest request) {
        // P6 口径：purpose 由功能入口预传入——本端点即功能入口，定死覆写防客户端误传
        if (request != null) {
            request.setPurpose(AiPurposeEnum.STRUCTURE);
        }
        return Result.success(chatService.chat(request, userIdResolver.getUserId()));
    }

    /**
     * 相似文章推荐端点（板块五 5.1 + 板块八 1.1：purpose 内部定死 SIMILAR；L0/L1 静态路由——
     * freeText 为空走 L0 直通固定相似检索 0 次 LLM，非空走 L1 条件提取管道，
     * 提取失败/超时降级 L0 并在响应 notice 标注「未识别自定义条件」，不报错）
     *
     * <p>【失败语义】RAG 缺席（rag.enabled=false）/取稿失败/检索失败/无命中 → 返回空卡片列表
     * 明确响应，不报错不降级抛错（F8 边界铁律）。</p>
     *
     * @param request 推荐请求（requestId/articleId 必传；freeText 可空触发 L1；用户身份取自登录态，匿名浏览为空）
     * @return 推荐响应（cards 卡片列表 + conditional 路由标识 + notice 降级标注）
     */
    @PostMapping("/similar")
    public Result<XiaoyueSimilarResponse> similar(@RequestBody XiaoyueSimilarRequest request) {
        return Result.success(similarRecommendService.similar(request, userIdResolver.getUserId()));
    }

    /**
     * 发布前查重端点（板块十二 4.2：创作侧发布前调用相似检索换调用时机——标题+正文
     * 经条件版语义检索，相似度 ≥ 外置阈值提示重复风险；0~1 次 LLM：explain=true 且
     * 命中时 1 次生成「哪里相似」说明；入口建设归 content 创作页，本端点只提供能力）
     *
     * <p>【失败语义】预算超限/检索失败抛 BizException 明确提示（查重失败不静默放行发布）；
     * 说明生成失败不中断主结果（explanation 置空）。</p>
     *
     * @param request 查重请求（requestId/title/body 必传，explain 可空；用户身份取自登录态，匿名可空）
     * @return 查重响应（risk/threshold/articles + 可选 explanation）
     */
    @PostMapping("/duplicate-check")
    public Result<DuplicateCheckResponse> duplicateCheck(@RequestBody DuplicateCheckRequest request) {
        return Result.success(duplicateCheckService.check(request.getRequestId(), userIdResolver.getUserId(), request));
    }

    /**
     * 多级改写建议端点（板块十五 15.1：purpose 内部定死 POLISH 复用写作辅助桶（不扩枚举），
     * scene=rewrite-{粒度} 区分粒度；TITLE 粒度承载板块十二 4.7「标题/开头多版建议」统一承载
     * ——articleId 入口时文章归属校验（AI-V6 ⑤/⑦ 口径）；建议态不自动写入（D4），
     * 展示前 DFA 闸门由调用方执行；仅登录用户可用（写作辅助不开匿名））
     *
     * @param request 改写请求（requestId/targetType 必传；原文 articleId 与 originalText 二选一）
     * @return 改写建议（候选版本数组 + 优化建议清单；建议态不自动落库）
     */
    @PostMapping("/rewrite")
    public Result<XiaoyueSuggestionResponse> rewrite(@RequestBody RewriteRequest request) {
        return Result.success(writingAssistService.rewrite(request, userIdResolver.getUserId()));
    }

    /**
     * 文章草稿生成端点（板块十五 15.2：purpose 内部定死 POLISH；AI 不落库——作者编辑后
     * 由前端带入 content 正常创建流程走完整 DFA + 机审主链路，AI 草稿不绕过送审；
     * 草稿仅参考、作者可关闭）
     *
     * @param request 草稿请求（requestId/theme 必传；大纲/要点与字数范围可空）
     * @return 草稿建议（draft 草稿正文 + outline 段落结构建议；建议态不自动落库）
     */
    @PostMapping("/draft")
    public Result<XiaoyueSuggestionResponse> draft(@RequestBody DraftRequest request) {
        return Result.success(writingAssistService.draft(request, userIdResolver.getUserId()));
    }
}
