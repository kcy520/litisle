package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.captcha.ChangeEmailVerifyDTO;
import com.litisle.litisleapi.dto.moderation.PunishAuditDTO;
import com.litisle.litisleapi.dto.moderation.PunishSpeakCheckDTO;
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

            @Override
            public Result<Void> verifyChangeEmail(ChangeEmailVerifyDTO dto) {
                // 安全关键校验（改绑邮箱防爆破），禁止降级：fail-fast 抛出异常，保留原始异常链
                log.error("远程调用 auth 模块校验改绑邮箱验证码失败！该校验为安全关键操作，禁止降级", cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "验证码服务暂不可用，请稍后重试", cause);
            }

            @Override
            public Result<PunishAuditDTO> getPunishAudit(Long userId) {
                // 对账数据禁降级：静默返回成功会让 sweeper 拿到假「无异常」结论而漏补漏，
                // fail-fast 上抛让 sweeper 感知本轮对账失败并重试
                log.error("远程调用 auth 模块处罚对账审计查询失败！对账数据禁降级，userId={}", userId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "对账审计服务暂不可用，请稍后重试", cause);
            }

            @Override
            public Result<Void> refreshPunishSnapshot(Long userId) {
                // 反向补解封为安全关键写操作（漏补解封=误禁持续），禁止降级：fail-fast 上抛
                log.error("远程调用 auth 模块反向补解封失败！补漏写操作禁止降级，userId={}", userId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "补解封服务暂不可用，请稍后重试", cause);
            }

            @Override
            public Result<PunishSpeakCheckDTO> checkSpeak(Long userId) {
                // 发布链路拦截校验禁降级：静默放行=漏禁风险（被禁用户照常发言），fail-fast 上抛
                log.error("远程调用 auth 模块发布链路禁言态校验失败！拦截校验禁降级，userId={}", userId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "发言校验服务暂不可用，请稍后重试", cause);
            }
        };
    }
}
