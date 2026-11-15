package com.litisle.litisleai.controller;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.service.AiChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 内部对话提供方端点（1.4：实现 litisle-api 契约 AiClient.chat，解锁 review 文本机审闭环）
 *
 * <p>【路径口径】/api/feign/ai/chat 为服务间前缀（网关不路由 /api/feign/**，仅服务间可用）；
 * 契约与降级 fail-fast 语义见 AiClient / AiClientFallbackFactory（litisle-api）。</p>
 *
 * <p>【职责边界】controller 只做协议转换与统一响应包装；purpose/requestId 校验、预算判定、
 * 计量留痕、模型调用全部收口在 AiChatService。防注入包裹与三分态映射由调用方（review）组织。</p>
 *
 * @author litisle
 */
@RestController
@RequiredArgsConstructor
public class AiChatController {

    /** 对话业务编排服务 */
    private final AiChatService aiChatService;

    /**
     * 文本对话（OpenAI 兼容通道转发，review 文本机审经此调 LLM）
     *
     * @param dto system+user 双段 prompt + purpose/requestId/scene 计量上下文
     * @return 模型原始文本输出（结构校验由调用方负责）
     */
    @PostMapping("/api/feign/ai/chat")
    public Result<com.litisle.litisleapi.dto.ai.AiChatResponseDTO> chat(
            @RequestBody com.litisle.litisleapi.dto.ai.AiChatRequestDTO dto) {
        return Result.success(aiChatService.chat(dto));
    }
}
