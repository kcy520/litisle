package com.litisle.litisleuser.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.litisle.commonsecurity.constant.RoleCode;
import com.litisle.commonsecurity.enums.RoleCodeEnum;
import com.litisle.litisleuser.domain.vo.RoleVO;
import com.litisle.litisleuser.dto.AuthorApplyDTO;
import com.litisle.litisleuser.dto.RoleAdjustDTO;
import com.litisle.litisleuser.dto.RoleApplyReviewDTO;
import com.litisle.litisleuser.service.UserRoleService;
import com.litisle.commoncore.result.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 角色/权限/申请/审核
 *
 * 角色约束：
 * - AUTHOR 与 LEGACY_AUTHOR 不能同时存在
 * - SUPER_ADMIN 通常不直接被普通 ADMIN 授予/移除
 * - ADMIN 不能修改 SUPER_ADMIN
 */
@RestController
@RequestMapping("/v1/user/roles")
@RequiredArgsConstructor
public class UserRoleController {

    private final UserRoleService userRoleService;

    /**
     * 角色枚举/可选角色列表
     */
    @GetMapping
    public Result<List<RoleVO>> roleList(@PathVariable Long userId) {

        userRoleService.listRoles();

        return Result.success();
    }

//    /**
//     * 当前用户拥有的角色
//     */
//    @GetMapping("/me")
//    @SaCheckLogin
//    public Result<List<RoleVO>> myRoles() {
//        return Result.success(userRoleService.currentUserRoles());
//    }

    /**
     * 查看某用户角色，后台用
     */
    @GetMapping("/users/{userId}")
    @SaCheckRole(
            value = {RoleCode.ADMIN, RoleCode.REVIEWER},
            mode = SaMode.OR
    )
    public Result<List<RoleVO>> userRoles(@PathVariable Long userId) {
        return Result.success(userRoleService.userRoles(userId));
    }

//    /**
//     * READER 申请 AUTHOR
//     */
//    @PostMapping("/apply")
//    @SaCheckLogin
//    public Result<Void> applyAuthor(@Valid @RequestBody AuthorApplyDTO dto) {
//        userRoleService.applyAuthor(dto);
//        return Result.success();
//    }

//    /**
//     * 审核角色申请
//     后续可能发生至消息队列？给审核员处理
//     */
//    @PostMapping("/apply/{applyId}/review")
//    @SaCheckRole(
//            value = {"REVIEWER", "ADMIN", "SUPER_ADMIN"},
//            mode = SaMode.OR
//    )
//    public Result<Void> reviewApply(
//            @PathVariable Long applyId,
//            @Valid @RequestBody RoleApplyReviewDTO dto
//    ) {
//        userRoleService.reviewApply(applyId, dto);
//        return Result.success();
//    }


}