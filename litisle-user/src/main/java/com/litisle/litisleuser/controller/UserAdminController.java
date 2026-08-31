package com.litisle.litisleuser.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;

import com.litisle.commoncore.result.PageResult;
import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.constant.PermissionCode;
import com.litisle.commonsecurity.constant.RoleCode;
import com.litisle.commonsecurity.enums.RoleCodeEnum;
import com.litisle.litisleuser.domain.dto.UserQueryDTO;
import com.litisle.litisleuser.domain.dto.UserStatusChangeDTO;
import com.litisle.litisleuser.domain.vo.UserAdminDetailVO;
import com.litisle.litisleuser.domain.vo.UserAdminVO;
import com.litisle.litisleuser.service.UserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 后台管理员 - 用户管理
 * <p>
 * 面向后台管理员（ADMIN / SUPER_ADMIN），与前台用户资料接口物理隔离。
 * 明文敏感数据（手机号/邮箱）查看请走 UserSensitiveController，本类不重复提供。
 *
 * 【整合后的接口清单】
 * 1. GET    /api/admin/users/page         分页查询用户列表    USER_READ
 * 2. GET    /api/admin/users/{id}         查询用户详情        USER_READ
 * 3. PUT    /api/admin/users/{id}         管理员修改用户资料   USER_UPDATE
 * 4. PATCH  /api/admin/users/{id}/status  启用/禁用/冻结       USER_BAN
 * 5. PUT    /api/admin/users/{id}/roles   分配角色            ROLE_ASSIGN（仅SUPER_ADMIN）
 * 6. PUT    /api/admin/users/{id}/vip     设置/延长/取消VIP    USER_UPDATE
 * 7. DELETE /api/admin/users/{id}         注销用户（软删除）  USER_DELETE
 */
@Tag(name = "后台用户管理", description = "仅供 ADMIN / SUPER_ADMIN，普通用户管理接口")
@RestController
@RequestMapping("/api/admin/users")
@SaCheckRole(RoleCode.ADMIN)
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    /**
     * 1. 分页查询用户列表（按用户名/状态/角色/时间筛选）
     */
    @Operation(summary = "分页查询用户列表")
    @GetMapping("/page")
    @SaCheckPermission(PermissionCode.USER_READ)
    public Result<PageResult<UserAdminVO>> page(@Valid UserQueryDTO query) {
        PageResult<UserAdminVO> page = userAdminService.page(query);
        return Result.success(page);
    }

    /**
     * 2. 查询用户详情（聚合角色、VIP、作者扩展资料）
     */
    @Operation(summary = "查询用户详情")
    @GetMapping("/{userId}")
    @SaCheckPermission(PermissionCode.USER_READ)
    public Result<UserAdminDetailVO> detail(@PathVariable Long userId) {
        UserAdminDetailVO detail = userAdminService.detail(userId);
        return Result.success(detail);
    }



    /**
     * 4. 变更用户状态（启用/禁用/冻结，含 frozenUntil）
     */
    @Operation(summary = "启用/禁用/冻结用户")
    @PatchMapping("/{userId}/status")
    @SaCheckPermission(PermissionCode.USER_BAN)
    public Result<Void> changeStatus(@PathVariable Long userId, @Valid @RequestBody UserStatusChangeDTO dto) {
        userAdminService.changeStatus(userId, dto);
        return Result.success();
    }

    /**
     * 6. 添加用户角色（增量）
     */
    @Operation(summary = "添加用户角色")
    @PostMapping("/{userId}/roles")
    @SaCheckPermission(PermissionCode.ROLE_ASSIGN)
    public Result<Void> addRoles(@PathVariable Long userId,
                                 @RequestBody List<String> roleCodes) {
        userAdminService.addRoles(userId, roleCodes);
        return Result.success();
    }

    /**
     * 7. 移除用户角色（增量）
     */
    @Operation(summary = "移除用户角色")
    @DeleteMapping("/{userId}/roles")
    @SaCheckPermission(PermissionCode.ROLE_ASSIGN)
    public Result<Void> removeRoles(@PathVariable Long userId,
                                    @RequestBody List<String> roleCodes) {
        userAdminService.removeRoles(userId, roleCodes);
        return Result.success();
    }

//    /**
//     *  todo 6. 管理 VIP（设置/延长/取消到期时间）
//     */
//    @Operation(summary = "管理用户 VIP")
//    @PutMapping("/{userId}/vip")
//    @SaCheckPermission(PermissionCode.USER_UPDATE)
//    public Result<Void> manageVip(@PathVariable Long userId, @Valid @RequestBody VipUpdateDTO dto) {
//        userAdminService.manageVip(userId, dto);
//        return Result.success();
//    }

    /**
     * 7. 注销用户（软删除，ADMIN）
     */
    @Operation(summary = "注销用户")
    @DeleteMapping("/{userId}")
    @SaCheckPermission(PermissionCode.USER_DELETE)
    public Result<Void> deactivateUser(@PathVariable Long userId) {
        //deactivate-停用/注销
        userAdminService.logicDeleteUserByAdmin(userId);
        return Result.success();
    }

}