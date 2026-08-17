package com.litisle.commonsecurity.satoken;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sa-Token 全局异常处理器
 *
 * 【设计说明】
 * 1. 与 common-core 的 GlobalExceptionHandler 职责分离：
 *    - GlobalExceptionHandler：通用异常（BizException、校验异常、兜底 Exception）
 *    - 本类：认证鉴权异常（NotLoginException、NotPermissionException 等）
 * 2. 所有异常统一返回 Result 格式，与项目规范一致
 * 3. 替代 Sa-Token 内置的 SaTokenExceptionHandler（返回格式不统一）
 *
 * 【优先级控制】
 * - @Order(Ordered.HIGHEST_PRECEDENCE)：确保本处理器优先于 Sa-Token 内置处理器
 *   和 GlobalExceptionHandler 的 Exception.class 兜底处理
 *
 * 【NotLoginException 子类型说明】
 * Sa-Token 通过 e.getType() 区分 5 种未登录场景：
 * - NOT_TOKEN         ：未携带 Token（请求头中无 Authorization）
 * - INVALID_TOKEN     ：Token 无效（格式错误、签名不匹配、被篡改）
 * - TOKEN_TIMEOUT     ：Token 已过期（超过 timeout 时间）
 * - BE_REPLACED       ：被顶下线（同账号在其他设备登录，is-concurrent=false 时触发）
 * - KICK_OUT          ：被踢下线（管理员主动调用 StpUtil.kickout()）
 *
 * 【日志规范】
 * - 认证鉴权异常属于"正常业务场景"（非系统错误），使用 log.warn 而非 log.error
 * - 不打印完整堆栈（warn 级别不输出堆栈），避免日志污染
 * - 注意：NotLoginException / NotPermissionException / NotRoleException 在 Sa-Token 1.45.0 中无 getLoginId() 方法，
 *   loginId 仅 DisableServiceException 支持（该异常直接携带被封禁的账号ID）
 *
 * @author litisle
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SaTokenExceptionHandler {

    /**
     * 处理未登录异常（含 5 种子类型）
     *
     * 【触发场景】
     * - 未携带 Token 访问需登录的接口
     * - Token 已过期、无效、被篡改
     * - 被顶下线或被踢下线
     *
     * @param e NotLoginException
     * @return 统一响应格式，错误码 UNAUTHORIZED(10002)
     */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLoginException(NotLoginException e) {
        String message = switch (e.getType()) {
            case NotLoginException.NOT_TOKEN ->
                    "未登录，请先登录";
            case NotLoginException.INVALID_TOKEN ->
                    "令牌无效，请重新登录";
            case NotLoginException.TOKEN_TIMEOUT ->
                    "登录已过期，请重新登录";
            case NotLoginException.BE_REPLACED ->
                    "账号已在其他设备登录，请重新登录";
            case NotLoginException.KICK_OUT ->
                    "账号已被管理员强制下线";
            default ->
                    "未登录或登录已过期";
        };
        log.warn("认证异常: type={}, message={}", e.getType(), message);
        return Result.error(ErrorCode.UNAUTHORIZED.getCode(), message);
    }

    /**
     * 处理权限不足异常
     *
     * 【触发场景】
     * - @SaCheckPermission("user:delete") 标注的方法被无权限用户调用
     * - StpUtil.checkPermission("user:delete") 主动校验失败
     *
     * @param e NotPermissionException
     * @return 统一响应格式，错误码 FORBIDDEN(10003)
     */
    @ExceptionHandler(NotPermissionException.class)
    public Result<Void> handleNotPermissionException(NotPermissionException e) {
        log.warn("权限不足: permission={}", e.getPermission());
        return Result.error(ErrorCode.FORBIDDEN);
    }

    /**
     * 处理角色不匹配异常
     *
     * 【触发场景】
     * - @SaCheckRole("ADMIN") 标注的方法被非管理员用户调用
     * - StpUtil.checkRole("ADMIN") 主动校验失败
     *
     * @param e NotRoleException
     * @return 统一响应格式，错误码 FORBIDDEN(10003)
     */
    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRoleException(NotRoleException e) {
        log.warn("角色不匹配: role={}", e.getRole());
        return Result.error(ErrorCode.FORBIDDEN);
    }

    /**
     * 处理账号已被封禁异常
     *
     * 【触发场景】
     * - 管理员调用 StpUtil.disable(loginId, time) 封禁账号后，用户尝试操作
     * - 封禁时长到期后自动解除，无需手动恢复
     *
     * @param e DisableServiceException
     * @return 统一响应格式，错误码 USER_FROZEN(20004)
     */
    @ExceptionHandler(DisableServiceException.class)
    public Result<Void> handleDisableServiceException(DisableServiceException e) {
        log.warn("账号已被封禁: level={}, disableTime={}秒", e.getLevel(), e.getDisableTime());
        return Result.error(ErrorCode.USER_FROZEN);
    }
}