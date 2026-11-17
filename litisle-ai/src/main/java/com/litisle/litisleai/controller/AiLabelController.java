package com.litisle.litisleai.controller;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.service.AiLabelService;
import com.litisle.litisleapi.dto.ai.AiLabelRequestDTO;
import com.litisle.litisleapi.dto.ai.AiLabelResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 内部打标提供方端点（板块八 1.3：实现 litisle-api 契约 AiClient#label，
 * 供 search 索引链路索引期标注 ai_labels）
 *
 * <p>【路径口径】/api/feign/ai/label 为服务间前缀（网关不路由 /api/feign/**，仅服务间可用）；
 * purpose=LABELING（索引期管线类独立预算桶，不开放用户端点）。</p>
 *
 * <p>【职责边界】controller 只做协议转换与统一响应包装；参数校验、预算判定、注册表拼装 prompt、
 * 词表校验回喂重试、计量留痕全部收口在 {@link AiLabelService}；打标失败 fail-fast 上抛，
 * 重试语义由 search 消费侧承担（不阻断索引主链路）。</p>
 *
 * @author litisle
 */
@RestController
@RequiredArgsConstructor
public class AiLabelController {

    /** 索引期打标编排服务 */
    private final AiLabelService aiLabelService;

    /**
     * 索引期打标（AiClient#label 契约实现，search 索引链路按维度注册表词表标注 ai_labels）
     *
     * @param dto 打标请求（requestId/title/content 必传）
     * @return ai_labels 维度标签集（key=维度名，value=词表值或语义短语）
     */
    @PostMapping("/api/feign/ai/label")
    public Result<AiLabelResponseDTO> label(@RequestBody AiLabelRequestDTO dto) {
        return Result.success(aiLabelService.label(dto));
    }
}
