package com.litisle.litisleuser.controller;

import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.annotation.Login;
import com.litisle.litisleuser.domain.vo.UserSelfVO;
import com.litisle.litisleuser.service.UserProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户资料
 * 负责昵称、头像、手机号、邮箱、简介、笔名。
 * */

@RestController
@RequestMapping("/v1/user/profile")
//@PreAuthorize("isAuthenticated()")//登录校验
@Login//自定义登录校验
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /**
     * 获取当前登录用户资料
     */
    @GetMapping("/me")
    public Result<UserSelfVO> me() {
        return Result.success(userProfileService.currentUserProfile());
    }

//    /**
//     * 根据 ID 获取公开资料
//     */
//    @GetMapping("/{userId}")
//    public Result<PublicUserProfileVO> publicProfile(@PathVariable Long userId) {
//        return Result.success(userProfileService.publicProfile(userId));
//    }
//
//    /**
//     * 修改基础资料
//     */
//    @PutMapping
//    public Result<Void> update(@Valid @RequestBody UserProfileUpdateDTO dto) {
//        userProfileService.updateCurrentUserProfile(dto);
//        return Result.success();
//    }
//
//    /**
//     * 上传/更新头像
//     * 文件实际上传可以到文件服务，user 模块只保存 SeaweedFS 返回的 file id/url
//     */
//    @PostMapping("/avatar")
//    public Result<AvatarVO> updateAvatar(@Valid @RequestBody AvatarUpdateDTO dto) {
//        return Result.success(userProfileService.updateAvatar(dto));
//    }
//
//    /**
//     * 管理员或本人查看脱敏手机号
//     * 明文查看单独接口，带审计
//     */
//    @GetMapping("/phone-clear")
//    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN') or isSelf()")
//    @AuditLog(action = "VIEW_PHONE_PLAINTEXT")
//    public Result<String> viewPlainPhone() {
//        return Result.success(userProfileService.viewCurrentUserPlainPhone());
//    }
}