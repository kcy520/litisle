package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.content.ArticleCounterUpdateDTO;
import com.litisle.litisleapi.dto.content.ArticleImageRefDTO;
import com.litisle.litisleapi.dto.content.ArticleIndexDocDTO;
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

            /**
             * 按图反查引用文章为图片复审再聚合核心读依赖 fail-fast：静默返回空列表会让
             * review 侧误判「无文章引用」跳过聚合，异常穿透到调用方由消费端重试
             */
            @Override
            public Result<List<Long>> listArticleIdsByFileId(String fileId) {
                log.error("远程调用 content 模块 listArticleIdsByFileId 失败！fileId = {}", fileId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "内容服务暂不可用，请稍后重试", cause);
            }

            @Override
            public Result<Void> updateCounters(ArticleCounterUpdateDTO dto) {
                log.error("远程调用 content 模块 updateCounters 失败！articleId = {}", dto, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "内容服务暂不可用，请稍后重试", cause);            }

            /**
             * 建索引回查为 search 索引同步核心读依赖 fail-fast：静默返回空数据会导致
             * 索引写入残缺文档/误删文档，异常穿透到消费端由延迟重试兜底
             */
            @Override
            public Result<ArticleIndexDocDTO> getArticleIndexDoc(Long articleId) {
                log.error("远程调用 content 模块 getArticleIndexDoc 失败！articleId = {}", articleId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "内容服务暂不可用，请稍后重试", cause);
            }
        };
    }
}
