package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.feign.AuthClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * 当 user 模块通过 AuthClient 远程调用 auth 模块失败时，执行这里的兜底逻辑。
 *
 * 【设计说明】
 * 注销用户时，强制下线属于"尽力而为"的旁路清理：即使 auth 模块不可用，
 * 也应返回成功以便主流程（软删除/审计日志）正常完成，避免因旁路失败影响核心操作。
 * 用户下次请求鉴权时，Sa-Token 的账号状态校验仍会拦截已注销/封禁账号。
 *
 * @author litisle
 */
@Slf4j
public class AuthClientFallbackFactory implements FallbackFactory<AuthClient> {

    @Override
    public AuthClient create(Throwable cause) {
        return new AuthClient() {
            @Override
            public Result<Void> forceLogout(Long userId) {
                log.error("远程调用 auth 模块强制下线失败！userId = {}, 将以降级策略放行", userId, cause);
                return Result.success();
            }
        };
    }
}
