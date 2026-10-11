package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.captcha.ChangeEmailVerifyDTO;
import com.litisle.litisleapi.dto.moderation.PunishAuditDTO;
import com.litisle.litisleapi.fallback.AuthClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

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

    /**
     * 校验“改绑邮箱”双码（图形验证码 → 邮箱验证码，用途 CHANGE_EMAIL，含防爆破）
     * <p>
     * 作用于：user 模块初次绑定邮箱 / 更换邮箱前，远程校验验证码。
     * 两次校验均一次性消费：图形码防暴力穷举，邮箱码失败达阈值自动失效强制重发。
     *
     * @param dto 图形码 uuid+code 与目标邮箱的邮箱验证码
     * @return 校验通过返回成功；任一校验不通过由 auth 端抛 BizException
     */
    @PostMapping("/api/feign/captcha/change-email/verify")
    Result<Void> verifyChangeEmail(@RequestBody ChangeEmailVerifyDTO dto);

    /**
     * 查询用户处罚对账审计快照（临时清单 B5，5-3.2）
     *
     * <p>【使用场景】双向对账 sweeper（挂 review 侧 6-1 调度）对每个 punish_log 生效封禁行
     * 回查 auth 侧实况（活跃会话态 + 本地快照态），一次请求支撑两个补漏方向判定：
     * 正向补踢（复用 forceLogout）/ 反向补解封（refreshPunishSnapshot）；只读无副作用</p>
     *
     * <p>【安全纪律】内部契约端点：网关不配置路由，防公网直查处罚审计态</p>
     *
     * @param userId 用户 ID
     * @return 审计快照（非 null）
     */
    @GetMapping("/api/feign/auth/punish-audit/{userId}")
    Result<PunishAuditDTO> getPunishAudit(@PathVariable("userId") Long userId);

    /**
     * 反向补解封：回查 review 权威态并覆盖 auth 本地禁言快照（B5，5-3.2）
     *
     * <p>【使用场景】sweeper 发现 auth 快照 banned=true 但 punish_log 无生效封禁行
     * （撤销处罚事件丢失）时调用——auth 内部经 ReviewClient 回查权威态覆盖快照
     * （SSOT：快照只能以回查覆盖，sweeper 不直写）；幂等，重复调用无害</p>
     *
     * @param userId 用户 ID
     * @return 无业务数据；回查失败由提供方抛异常穿透
     */
    @PostMapping("/api/feign/auth/punish-audit/{userId}/refresh-snapshot")
    Result<Void> refreshPunishSnapshot(@PathVariable("userId") Long userId);

}
