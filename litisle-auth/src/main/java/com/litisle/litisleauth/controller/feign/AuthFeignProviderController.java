package com.litisle.litisleauth.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.desensitize.SensitiveStrategy;
import com.litisle.litisleapi.dto.captcha.ChangeEmailVerifyDTO;
import com.litisle.litisleapi.dto.moderation.PunishAuditDTO;
import com.litisle.litisleapi.dto.moderation.PunishSpeakCheckDTO;
import com.litisle.litisleapi.feign.AuthClient;
import com.litisle.litisleauth.enums.EmailCodeUsage;
import com.litisle.litisleauth.service.AuthService;
import com.litisle.litisleauth.service.CaptchaService;
import com.litisle.litisleauth.service.PunishAuditService;
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
    private final PunishAuditService punishAuditService;

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

    /**
     * 查询用户处罚对账审计快照
     * @param userId 用户 ID
     * @return
     */
    @Override
    public Result<PunishAuditDTO> getPunishAudit(Long userId) {
        // B5 对账审计只读查询：活跃会话态 + 本地快照态，供 review 侧 sweeper 判定补漏方向
        log.info("Feign 处罚对账审计查询: userId={}", userId);
        return Result.success(punishAuditService.getPunishAudit(userId));
    }

    /**
     * 反向补解封快照 = 发现本地缓存还记着"用户被封"，
     * 但 review 权威源已经解封了，于是主动从 review 拉最新状态覆盖本地缓存，把本地也"解封"掉。
     *
     * @param userId 用户 ID
     * @return
     */
    @Override
    public Result<Void> refreshPunishSnapshot(Long userId) {
        // B5 反向补解封：SSOT 收口，仅经回查 review 权威态覆盖快照，禁止直写
        log.info("Feign 反向补解封快照刷新: userId={}", userId);
        //刷新快照
        punishAuditService.refreshPunishSnapshot(userId);
        return Result.success();
    }

    /**
     * 发布链路禁言态校验
     * @param userId 发言用户 ID
     * @return
     */
    @Override
    public Result<PunishSpeakCheckDTO> checkSpeak(Long userId) {
        // 5-2.4 发布链路拦截校验：决策收口 PunishAuditService.checkSpeak，调用方禁二次判定
        log.info("Feign 发布链路禁言态校验: userId={}", userId);
        return Result.success(punishAuditService.checkSpeak(userId));
    }

}
