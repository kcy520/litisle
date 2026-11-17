package com.litisle.litisleapi.feign;


import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.ai.AiChatRequestDTO;
import com.litisle.litisleapi.dto.ai.AiChatResponseDTO;
import com.litisle.litisleapi.dto.ai.AiLabelRequestDTO;
import com.litisle.litisleapi.dto.ai.AiLabelResponseDTO;
import com.litisle.litisleapi.fallback.AiClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * AI 服务 Feign 客户端（跨服务契约，§4.1「litisle-ai 提供 LLM 调用通道」）
 *
 * 【设计说明】
 * 1. 契约先行：本接口与 DTO、降级工厂已就绪；提供方端点待实现——
 *    TODO litisle-ai 模块实现 POST /api/feign/ai/chat（OpenAI 兼容转发，
 *    base-url/api-key/model 外置 Nacos，勿在代码或 yml 提交密钥）
 * 2. 路径前缀 /api/feign/ai 遵循规范：网关未注册该前缀路由，仅服务间可用
 * 3. LLM 调用属审核链路核心读依赖，降级 fail-fast（见 AiClientFallbackFactory）：
 *    静默降级会让调用方误判「审核通过」，由调用方重试/熔断后转人审
 * 4. 超时口径：调用方按 litisle.moderation.llm 相关配置对齐
 *    feign.client.config.litisle-ai.read-timeout（Nacos 侧统一）
 *
 * 【调用方】review 模块（文本机审）、search 模块（索引期打标）、未来 ai/RAG 能力
 *
 * @author litisle
 */
@FeignClient(
        name = "litisle-ai",
        configuration = FeignApiConfig.class,
        fallbackFactory = AiClientFallbackFactory.class
)
public interface AiClient {

    /**
     * 文本对话（OpenAI 兼容通道转发）
     * <p>提供方内部限制输入输出长度（防 prompt 放大），返回 content 为模型原始文本，
     * 结构校验由调用方负责
     */
    @PostMapping("/api/feign/ai/chat")
    Result<AiChatResponseDTO> chat(@RequestBody AiChatRequestDTO dto);

    /**
     * 索引期打标（板块八 1.3：search 索引链路按维度注册表词表标注 ai_labels）
     *
     * <p>【设计说明】
     * 1. purpose=LABELING（索引期管线类独立预算桶，不开放用户端点）；
     * 2. 低置信维度不输出（缺失即未标注），ai 侧不硬造标签；
     * 3. fail-fast：预算超限/校验重试耗尽抛明确错误码，重试语义由 search 消费侧承担
     *    （索引链路打标失败不阻断主链路，ai_labels 留空降级）</p>
     *
     * @param dto 打标请求（requestId/title/content 必传）
     * @return ai_labels 维度标签集（key=维度名，value=词表值或语义短语）
     */
    @PostMapping("/api/feign/ai/label")
    Result<AiLabelResponseDTO> label(@RequestBody AiLabelRequestDTO dto);
}
