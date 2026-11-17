package com.litisle.litisleapi.fallback;


import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.ai.AiChatRequestDTO;
import com.litisle.litisleapi.dto.ai.AiChatResponseDTO;
import com.litisle.litisleapi.dto.ai.AiLabelRequestDTO;
import com.litisle.litisleapi.dto.ai.AiLabelResponseDTO;
import com.litisle.litisleapi.feign.AiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * AiClient 降级工厂
 *
 * 【降级语义】LLM 通道是机审核心读依赖：降级返回 null/空会让调用方误判「无审核结论」；
 * fail-fast 穿透到调用方（review 适配器捕获后按 SUSPICIOUS 宁疑不放转人审），
 * 与「重试耗尽转人审」的降级链（§2.7）衔接
 *
 * @author litisle
 */
@Slf4j
public class AiClientFallbackFactory implements FallbackFactory<AiClient> {

    @Override
    public AiClient create(Throwable cause) {
        return new AiClient() {

            /**
             * 核心读依赖 fail-fast：静默降级会破坏「LLM 失败必须转人审」的审核语义
             */
            @Override
            public Result<AiChatResponseDTO> chat(AiChatRequestDTO dto) {
                log.error("远程调用 ai 模块 chat 失败！promptChars = {}",
                        dto == null || dto.getUserPrompt() == null ? 0 : dto.getUserPrompt().length(), cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "AI 服务暂不可用，请稍后重试", cause);
            }

            /**
             * 索引期打标 fail-fast（板块八 1.3）：降级返回空标签会让索引链路误判「无标签可写」，
             * 异常穿透到调用方由 search 侧决策（不阻断索引主链路，ai_labels 留空降级）
             */
            @Override
            public Result<AiLabelResponseDTO> label(AiLabelRequestDTO dto) {
                log.error("远程调用 ai 模块 label 失败！requestId = {}",
                        dto == null ? null : dto.getRequestId(), cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "AI 服务暂不可用，请稍后重试", cause);
            }
        };
    }
}
