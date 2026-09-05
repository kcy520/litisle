package com.litisle.litisleuser.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.litisle.commonsecurity.constant.RoleCode;
import com.litisle.litisleuser.domain.vo.RoleVO;
import com.litisle.litisleuser.dto.AuthorApplyDTO;
import com.litisle.litisleuser.dto.RoleAdjustDTO;
import com.litisle.litisleuser.dto.RoleApplyReviewDTO;
import com.litisle.litisleuser.service.UserRoleService;
import com.litisle.commoncore.result.Result;
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
@RequestMapping("/api/user/roles")
@RequiredArgsConstructor
public class UserRoleController {

    private final UserRoleService userRoleService;

    /** 角色枚举
     * 系统里有哪些角色？（角色字典）
     * */
    @GetMapping
    public Result<List<RoleVO>> roleList() {
        List<RoleVO> roleVOS = userRoleService.listRoles();
        return Result.success(roleVOS);
    }

    /** 当前用户角色
     * 我当前有哪些角色？（用户身份）
     * */
    @GetMapping("/me")
    public Result<List<RoleVO>> myRoles() {
        List<RoleVO> roleVOList = userRoleService.currentUserRoles();
        return Result.success(roleVOList);
    }

    /**
     * 查看某用户角色，后台用
     */
    @GetMapping("/users/{userId}")
    @SaCheckRole(
            value = {RoleCode.ADMIN, RoleCode.REVIEWER},
            mode = SaMode.OR
    )
    public Result<List<RoleVO>> userRoles(@PathVariable Long userId) {
        List<RoleVO> roleVOS = userRoleService.userRoles(userId);
        return Result.success(roleVOS);
    }

//    /**
//     * 申请作者角色
//     * @param dto
//     * @return
//     */
//    @PostMapping("/apply-author")
//    public Result<Void> applyAuthor(@Valid @RequestBody AuthorApplyDTO dto) {
//        userRoleService.applyAuthor(dto);
//        return Result.success();
//    }

//    /**
//     * 审核角色申请
//     后续可能发生至消息队列？给审核员处理
//    @PostMapping("/apply-author/{applyId}/review")
//    @SaCheckRole(
//            value = {RoleCode.REVIEWER, RoleCode.ADMIN, RoleCode.SUPER_ADMIN},
//            mode = SaMode.OR
//    )
//    public Result<Void> reviewAuthorApply(
//            @PathVariable Long applyId,
//            @Valid @RequestBody RoleApplyReviewDTO dto
//    ) {
//        userRoleService.reviewAuthorApply(applyId, dto);
//        return Result.success();
//    }


}