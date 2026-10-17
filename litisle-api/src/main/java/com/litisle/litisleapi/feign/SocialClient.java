package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.social.MessageDTO;
import com.litisle.litisleapi.fallback.SocialClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 私信服务 Feign 客户端（跨服务契约）
 *
 * 【设计说明】
 * 1. 契约先行：本接口与 DTO、降级工厂已就绪；提供方端点待 social 服务私信域实现
 *    （私信归属 litisle-social，无独立 message 模块）
 *    TODO litisle-social 实现 GET /api/feign/message/get（bizId 回查私信正文）
 * 2. 路径前缀 /api/feign/message 遵循规范：网关未注册该前缀路由，仅服务间可用
 * 3. 回查正文是机审核心读依赖，降级 fail-fast（见 MessageClientFallbackFactory）
 *
 * 【调用方】review 模块（私信机审回查正文）、content 模块（visibility=3 仅关注者判定回查）
 *
 * @author litisle
 */
@FeignClient(
        name = "litisle-social",
        configuration = FeignApiConfig.class,
        fallbackFactory = SocialClientFallbackFactory.class
)
public interface SocialClient {

    /**
     * 回查私信正文
     *
     * @param bizId 私信主键
     * @return 私信 DTO；不存在时提供方返回 Result.fail，调用方判码处理
     */
    @GetMapping("/api/feign/message/get")
    Result<MessageDTO> getContent(@RequestParam("bizId") Long bizId);

    /**
     * 回查关注关系（content visibility=3 仅关注者判定链依赖，[4.1]）
     *
     * <p>路径前缀 /api/feign/follow 遵循规范：网关未注册该前缀路由，仅服务间可用</p>
     * <p>TODO litisle-social 实现 GET /api/feign/follow/check（ownerId-viewerId 关注关系判定）</p>
     *
     * @param ownerId  被关注者（文章作者）ID
     * @param viewerId 关注者（查看者）ID
     * @return true-viewer 已关注 owner；数据缺失/参数异常返回 fail，调用方按非关注者处理（宁严勿松）
     */
    @GetMapping("/api/feign/follow/check")
    Result<Boolean> isFollowing(@RequestParam("ownerId") Long ownerId, @RequestParam("viewerId") Long viewerId);
}
