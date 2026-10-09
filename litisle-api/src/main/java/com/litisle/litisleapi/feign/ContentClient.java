package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.content.ArticleImageRefDTO;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.fallback.ContentClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 内容服务 Feign 客户端（跨服务契约）
 *
 * 【设计说明】
 * 1. 契约先行：本接口与 DTO、降级工厂已就绪；提供方端点待 content 服务实现
 *    TODO litisle-content 实现 GET /api/feign/content/get（bizType+bizId 回查正文）
 * 2. 路径前缀 /api/feign/content 遵循规范：网关未注册该前缀路由，仅服务间可用
 * 3. 回查正文是机审核心读依赖，降级 fail-fast（见 ContentClientFallbackFactory）：
 *    静默降级会让 review 侧误判「无内容可审」，由消费端不 ACK 进延迟重试
 *
 * 【调用方】review 模块（文本机审回查正文）
 *
 * @author litisle
 */
@FeignClient(
        name = "litisle-content",
        configuration = FeignApiConfig.class,
        fallbackFactory = ContentClientFallbackFactory.class
)
public interface ContentClient {

    /**
     * 回查内容正文
     *
     * @param bizType 业务类型（1-文章 2-评论）
     * @param bizId   业务主键
     * @return 内容 DTO；内容不存在时提供方返回 Result.fail，调用方判码处理
     */
    @GetMapping("/api/feign/content/get")
    Result<ContentDTO> getContent(@RequestParam("bizType") Integer bizType,
                                  @RequestParam("bizId") Long bizId);

    /**
     * 反查文章配图引用列表（review 侧文章配图复审 ARTICLE_IMG_RE_REVIEW 用）
     *
     * <p>【使用场景】review 消费 moderation.image.submit（scene=ARTICLE_IMG_RE_REVIEW）后，
     * 按文章 bizId 反查该文章登记的 article_image 类型配图引用（fileId + file_meta.id），
     * 逐张经 FileClient.getContent(fileId) 内部字节流取图送审（严禁按用户 URL 抓取，SSRF 防线），
     * 单图结论落 moderation_record（biz_id=file_meta.id）</p>
     *
     * <p>【设计说明】
     * 1. 契约先行：本接口与 DTO、降级工厂已就绪；提供方端点待 content 服务实现
     *    TODO litisle-content 实现 GET /api/feign/content/article-images（按文章ID回查配图列表）
     * 2. 路径前缀 /api/feign/content 遵循规范：网关未注册该前缀路由，仅服务间可用
     * 3. 反查配图列表是配图复审机审核心读依赖，降级 fail-fast（见 ContentClientFallbackFactory）：
     *    静默降级返回空列表会让复审「无图可审」误判通过，由消费端不 ACK 进延迟重试</p>
     *
     * @param articleId 文章ID
     * @return 配图引用列表（按登记顺序；未登记配图时返回空列表）；文章不存在时提供方返回 Result.fail
     */
    @GetMapping("/api/feign/content/article-images")
    Result<List<ArticleImageRefDTO>> listArticleImages(@RequestParam("articleId") Long articleId);
}
