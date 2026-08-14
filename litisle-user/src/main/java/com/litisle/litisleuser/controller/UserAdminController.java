//package com.litisle.litisleuser.controller;
//
//
//import com.litisle.commonsecurity.annotation.RequireRole;
//import com.litisle.litisleuser.enums.RoleType;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
////后台管理用户，不建议和前台资料接口混在一个类里。URL 可以加 /admin。
//@RestController
//@RequestMapping("/user/admin/users")
////@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
//@RequireRole(roles = {Role, "EDITOR"}, logical = RequireRole.Logical.OR)
//public class UserAdminController {
//
//    private final UserAdminService userAdminService;
//
//    public UserAdminController(UserAdminService userAdminService) {
//        this.userAdminService = userAdminService;
//    }
//
//    /**
//     * 分页查询用户
//     */
//    @GetMapping
//    public CommonResult<PageVO<UserAdminVO>> page(@Valid UserQueryDTO query) {
//        return CommonResult.ok(userAdminService.page(query));
//    }
//
//    /**
//     * 查看用户详情
//     */
//    @GetMapping("/{userId}")
//    public CommonResult<UserAdminDetailVO> detail(@PathVariable Long userId) {
//        return CommonResult.ok(userAdminService.detail(userId));
//    }
//
//    /**
//     * 管理员修改用户资料
//     */
//    @PutMapping("/{userId}")
//    public CommonResult<Void> updateByAdmin(
//            @PathVariable Long userId,
//            @Valid @RequestBody UserAdminUpdateDTO dto
//    ) {
//        userAdminService.updateByAdmin(userId, dto);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 冻结/解冻用户
//     */
//    @PatchMapping("/{userId}/status")
//    public CommonResult<Void> changeStatus(
//            @PathVariable Long userId,
//            @Valid @RequestBody UserStatusChangeDTO dto
//    ) {
//        userAdminService.changeStatus(userId, dto);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 逻辑注销用户，后台强制注销也可用
//     */
//    @DeleteMapping("/{userId}")
//    public CommonResult<Void> logicDelete(@PathVariable Long userId) {
//        userAdminService.logicDelete(userId);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 管理员临时查看明文手机号
//     */
//    @GetMapping("/{userId}/phone-clear")
//    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
//    @AuditLog(action = "ADMIN_VIEW_USER_PHONE_PLAINTEXT")
//    public CommonResult<String> adminViewPlainPhone(@PathVariable Long userId) {
//        return CommonResult.ok(userAdminService.viewPlainPhone(userId));
//    }

/* ================= 管理员查看 ================= */
//
///**
// * 管理员查看指定用户手机号
// */
//@GetMapping("/users/{userId}/phone")
//@RequirePermission(perms = {PermissionEnum.SENSITIVE_PHONE_READ})
//@AuditLog(
//        moudle = AuditModule.SENSITIVE,
//        operation = AuditOperation.USER_VIEW_PHONE_ADMIN,
//        saveRequestData = false,
//        saveResponseData = false
//)
//public Result<String> userPhone(@PathVariable Long userId) {
//    return Result.success(userProfileService.viewUserPlainPhone(userId));
//}
//
///**
// * 管理员查看指定用户邮箱
// */
//@GetMapping("/users/{userId}/email")
//@RequirePermission(perms = {PermissionEnum.SENSITIVE_EMAIL_READ})
//@AuditLog(
//        moudle = AuditModule.SENSITIVE,
//        operation = AuditOperation.USER_VIEW_EMAIL_ADMIN,
//        saveRequestData = false,
//        saveResponseData = false
//)
//public Result<String> userEmail(@PathVariable Long userId) {
//    return Result.success(userProfileService.viewUserPlainEmail(userId));
//}
//
///**
// * 管理员查看指定用户密码
// */
//@GetMapping("/users/{userId}/password")
////    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
//@RequirePermission(perms = {PermissionEnum.SENSITIVE_PASSWORD_READ})
//@AuditLog(
//        moudle = AuditModule.SENSITIVE,
//        operation = AuditOperation.USER_VIEW_PASSWORD_ADMIN,
//        saveRequestData = false,
//        saveResponseData = false
//)
//public Result<String> userPassword(@PathVariable Long userId) {
//    return Result.success(userProfileService.viewUserPlainPassword(userId));
//}
//}