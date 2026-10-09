package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.content.ArticleImageRefDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.feign.ContentClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * ContentClient 降级工厂
 *
 * 【降级语义】fail-fast：回查正文是机审核心读依赖，读不到不能当「无内容」放行，
 * 异常穿透到调用方（review 侧不 ACK 进延迟重试/熔断后转人审）
 *
 * @author litisle
 */
@Slf4j
public class ContentClientFallbackFactory implements FallbackFactory<ContentClient> {

    @Override
    public ContentClient create(Throwable cause) {
        return new ContentClient() {

            /**
             * 机审回查核心读依赖 fail-fast：静默返回 null 会让 review 侧误判「无内容可审」，
             * 快速失败由消费端重试
             */
            @Override
            public Result<ContentDTO> getContent(Integer bizType, Long bizId) {
                log.error("远程调用 content 模块 getContent 失败！bizType = {}, bizId = {}", bizType, bizId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "内容服务暂不可用，请稍后重试", cause);
            }

            /**
             * 反查文章配图列表为配图复审机审核心读依赖 fail-fast：静默返回空列表会让
             * review 侧复审「无图可审」，异常穿透到调用方由消费端重试
             */
            @Override
            public Result<List<ArticleImageRefDTO>> listArticleImages(Long articleId) {
                log.error("远程调用 content 模块 listArticleImages 失败！articleId = {}", articleId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "内容服务暂不可用，请稍后重试", cause);
            }
        };
    }
}
