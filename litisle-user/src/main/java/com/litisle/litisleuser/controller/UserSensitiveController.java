package com.litisle.litisleuser.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.litisle.commoncore.enums.AuditModule;
import com.litisle.commoncore.enums.AuditOperation;
import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.audit.AuditLog;
import com.litisle.commonsecurity.constant.PermissionCode;
import com.litisle.commonsecurity.enums.PermissionEnum;
import com.litisle.litisleuser.service.UserProfileService;
import org.springframework.web.bind.annotation.*;

/**
 * 当前用户敏感信息
 * 仅用于明文/高风险数据查看，与用户资料接口物理隔离。
 *
 * 【说明】
 * - 本人查看：/sensitive/me/*
 * - 管理员查看：/sensitive/users/{userId}/*
 * - 所有接口不记录请求/返回值（合规要求）
 */
@RestController
@RequestMapping("/sensitive")
@SaCheckLogin//登录校验
public class UserSensitiveController {

    private final UserProfileService userProfileService;

    public UserSensitiveController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    //todo  修改敏感信息可能添加短信验证码校验，后续数据库密文存储，还需要解密

    /* ================= 本人查看 ================= */

    /**
     * 查看本人手机号（明文）
     */
    @GetMapping("/me/phone")
    @AuditLog(
        moudle = AuditModule.SENSITIVE,
        operation = AuditOperation.USER_VIEW_PHONE_SELF,
        saveRequestData = false,
        saveResponseData = false
    )
    public Result<String> myPhone() {
        String phone = userProfileService.viewCurrentUserPlainPhone();
        return Result.success();
    }

    /**
     * 查看本人邮箱（明文）
     */
    @GetMapping("/me/email")
    @AuditLog(
        moudle = AuditModule.SENSITIVE,
        operation = AuditOperation.USER_VIEW_EMAIL_SELF,
        saveRequestData = false,
        saveResponseData = false
    )
    public Result<String> myEmail() {
        return Result.success(userProfileService.viewCurrentUserPlainEmail());
    }

    /**
     * 查看本人密码（明文/脱敏）
     */
    @GetMapping("/me/password")
    @AuditLog(
        moudle = AuditModule.SENSITIVE,
        operation = AuditOperation.USER_VIEW_PASSWORD_SELF,
        saveRequestData = false,
        saveResponseData = false
    )
    public Result<String> myPassword() {
        return Result.success(userProfileService.viewCurrentUserPlainPassword());
    }

    /* ================= 管理员查看 ================= */

    /**
     * 管理员查看指定用户手机号
     */
    @GetMapping("/users/{userId}/phone")
    @SaCheckPermission(PermissionCode.SENSITIVE_PHONE_READ)
    @AuditLog(
        moudle = AuditModule.SENSITIVE,
        operation = AuditOperation.USER_VIEW_PHONE_ADMIN,
        saveRequestData = false,
        saveResponseData = false
    )
    public Result<String> userPhone(@PathVariable Long userId) {
        return Result.success(userProfileService.viewUserPlainPhone(userId));
    }

    /**
     * 管理员查看指定用户邮箱
     */
//    @RequirePermission(perms={PermissionEnum.SENSITIVE_PHONE_READ})
    @GetMapping("/users/{userId}/email")
    @SaCheckPermission(PermissionCode.SENSITIVE_EMAIL_READ)
    @AuditLog(
        moudle = AuditModule.SENSITIVE,
        operation = AuditOperation.USER_VIEW_EMAIL_ADMIN,
        saveRequestData = false,
        saveResponseData = false
    )
    public Result<String> userEmail(@PathVariable Long userId) {
        return Result.success(userProfileService.viewUserPlainEmail(userId));
    }

    /**
     * 管理员查看指定用户密码
     */
    @GetMapping("/users/{userId}/password")
    @SaCheckPermission(PermissionCode.SENSITIVE_PASSWORD_READ)
    @AuditLog(
        moudle = AuditModule.SENSITIVE,
        operation = AuditOperation.USER_VIEW_PASSWORD_ADMIN,
        saveRequestData = false,
        saveResponseData = false
    )
    public Result<String> userPassword(@PathVariable Long userId) {
        return Result.success(userProfileService.viewUserPlainPassword(userId));
    }
}