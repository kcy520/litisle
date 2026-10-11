package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.message.MessageDTO;
import com.litisle.litisleapi.feign.SocialClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * MessageClient 降级工厂
 *
 * 【降级语义】fail-fast：回查私信正文是机审核心读依赖，读不到不能当「无内容」放行，
 * 异常穿透到调用方（review 侧不 ACK 进延迟重试/熔断后转人审）
 *
 * @author litisle
 */
@Slf4j
public class MessageClientFallbackFactory implements FallbackFactory<SocialClient> {

    @Override
    public SocialClient create(Throwable cause) {
        return new SocialClient() {

            /**
             * 机审回查核心读依赖 fail-fast：与 ContentClientFallbackFactory 对称
             */
            @Override
            public Result<MessageDTO> getContent(Long bizId) {
                log.error("远程调用 message 模块 getContent 失败！bizId = {}", bizId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "私信服务暂不可用，请稍后重试", cause);
            }
        };
    }
}
