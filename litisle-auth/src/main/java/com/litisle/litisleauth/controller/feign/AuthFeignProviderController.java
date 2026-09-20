package com.litisle.litisleauth.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.desensitize.SensitiveStrategy;
import com.litisle.litisleapi.dto.captcha.ChangeEmailVerifyDTO;
import com.litisle.litisleapi.feign.AuthClient;
import com.litisle.litisleauth.enums.EmailCodeUsage;
import com.litisle.litisleauth.service.AuthService;
import com.litisle.litisleauth.service.CaptchaService;
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
    private final CaptchaService captchaService;

    @Override
    public Result<Void> forceLogout(Long userId) {
        log.info("Feign 强制下线用户: userId={}", userId);
        authService.forceLogout(userId);
        return Result.success();
    }

    @Override
    public Result<Void> verifyChangeEmail(ChangeEmailVerifyDTO dto) {
        log.info("Feign 校验改绑邮箱验证码: email={}", SensitiveStrategy.EMAIL.desensitize(dto.getEmail()));
        // 先校验图形验证码（一次性，防暴力穷举）
        captchaService.verifyImgCode(dto.getImgUuid(), dto.getImgCode());
        // 再校验邮箱验证码（用途 CHANGE_EMAIL，失败达阈值自动失效重发）
        captchaService.verifyEmailCode(dto.getEmail(), EmailCodeUsage.CHANGE_EMAIL, dto.getEmailCode());
        return Result.success();
    }

}
