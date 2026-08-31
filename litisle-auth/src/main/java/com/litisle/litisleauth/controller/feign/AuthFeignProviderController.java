package com.litisle.litisleauth.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.feign.AuthClient;
import com.litisle.litisleauth.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

/**
 * AuthClient Feign 契约实现（供 user 模块远程调用）
 * <p>
 * 注意：路径、HTTP 方法由 AuthClient 接口上的映射注解决定。
 *
 * @author litisle
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class AuthFeignProviderController implements AuthClient {

    private final AuthService authService;

    @Override
    public Result<Void> forceLogout(Long userId) {
        log.info("Feign 强制下线用户: userId={}", userId);
        authService.forceLogout(userId);
        return Result.success();
    }

}
