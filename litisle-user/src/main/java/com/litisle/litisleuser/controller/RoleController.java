//package com.litisle.litisleuser.controller;
//
////│   ├── RoleController.java            // 角色/权限/申请/审核
////READER / AUTHOR / LEGACY_AUTHOR / REVIEWER / ADMIN / SUPER_ADMIN
//AUTHOR 与 LEGACY_AUTHOR 不能同时存在
//SUPER_ADMIN 通常不直接被普通 ADMIN 授予/移除
//ADMIN 不能修改 SUPER_ADMIN
//@RestController
//@RequestMapping("/v1/user/roles")
//public class UserRoleController {
//
//    private final UserRoleService userRoleService;
//
//    public UserRoleController(UserRoleService userRoleService) {
//        this.userRoleService = userRoleService;
//    }
//
//    /**
//     * 角色枚举/可选角色列表
//     */
//    @GetMapping
//    public CommonResult<List<RoleVO>> roleList() {
//        return CommonResult.ok(userRoleService.listRoles());
//    }
//
//    /**
//     * 当前用户拥有的角色
//     */
//    @GetMapping("/me")
//    @PreAuthorize("isAuthenticated()")
//    public CommonResult<List<RoleVO>> myRoles() {
//        return CommonResult.ok(userRoleService.currentUserRoles());
//    }
//
//    /**
//     * 查看某用户角色，后台用
//     */
//    @GetMapping("/users/{userId}")
//    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'REVIEWER')")
//    public CommonResult<List<RoleVO>> userRoles(@PathVariable Long userId) {
//        return CommonResult.ok(userRoleService.userRoles(userId));
//    }
//
//    /**
//     * READER 申请 AUTHOR
//     */
//    @PostMapping("/apply")
//    @PreAuthorize("isAuthenticated()")
//    public CommonResult<Void> applyAuthor(@Valid @RequestBody AuthorApplyDTO dto) {
//        userRoleService.applyAuthor(dto);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 审核角色申请
//     */
//    @PostMapping("/apply/{applyId}/review")
//    @PreAuthorize("hasAnyRole('REVIEWER', 'ADMIN', 'SUPER_ADMIN')")
//    public CommonResult<Void> reviewApply(
//            @PathVariable Long applyId,
//            @Valid @RequestBody RoleApplyReviewDTO dto
//    ) {
//        userRoleService.reviewApply(applyId, dto);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 管理员直接绑定/解绑角色
//     */
//    @PatchMapping("/users/{userId}")
//    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
//    public CommonResult<Void> adjustRoles(
//            @PathVariable Long userId,
//            @Valid @RequestBody RoleAdjustDTO dto
//    ) {
//        userRoleService.adjustRoles(userId, dto);
//        return CommonResult.ok();
//    }
//}