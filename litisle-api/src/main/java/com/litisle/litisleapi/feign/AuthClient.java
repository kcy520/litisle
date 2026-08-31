package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.fallback.AuthClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 认证服务 Feign 客户端
 * <p>
 * 供微服务内部调用 auth 模块的接口（消息下发、强制下线等）。
 *
 * 【说明】
 * 1. 本接口定义在 litisle-api 共享模块，各调用方依赖该模块并启用 @EnableFeignClients 即可注入使用
 * 2. 提供方在 auth 模块通过实现本接口暴露端点（路径由本接口的映射注解决定）
 *
 * @author litisle
 */
@FeignClient(
        name = "litisle-auth",
        configuration = FeignApiConfig.class,
        fallbackFactory = AuthClientFallbackFactory.class
)
public interface AuthClient {

    /**
     * 强制下线指定用户的全部在线会话
     * <p>
     * 作用于：Sa-Token 会话、Redis 设备记录、refreshToken。
     * 供 user 模块在管理员注销/封号用户时调用，确保用户被强制下线。
     *
     * @param userId 目标用户 ID
     * @return 无业务数据；失败由 FallbackFactory 兜底，不影响主流程
     */
    @DeleteMapping("/api/feign/user/{userId}/sessions")
    Result<Void> forceLogout(@PathVariable("userId") Long userId);

}
