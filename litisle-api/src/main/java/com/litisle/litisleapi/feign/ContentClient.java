package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.fallback.ContentClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

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
}
