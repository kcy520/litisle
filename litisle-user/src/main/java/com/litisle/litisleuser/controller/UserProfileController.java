package com.litisle.litisleuser.controller;

import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.login.Login;
import com.litisle.litisleuser.domain.dto.UserProfileUpdateDTO;
import com.litisle.litisleuser.domain.vo.UserPublicVO;
import com.litisle.litisleuser.domain.vo.UserSelfVO;
import com.litisle.litisleuser.domain.vo.UserUpdateProfileVO;
import com.litisle.litisleuser.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 当前用户资料
 * 负责昵称、头像、手机号、邮箱、简介、笔名。
 * */

@RestController
@RequestMapping("/user/profile")
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

    /**
     * 根据 ID 获取公开资料
     */
    @GetMapping("/{userId}")
    public Result<UserPublicVO> publicProfile(@PathVariable Long userId) {
        return Result.success(userProfileService.publicProfile());
    }

    /**
     * 修改基础资料
     */
    @PutMapping
    public Result<UserUpdateProfileVO> update(@Valid @RequestBody UserProfileUpdateDTO dto) {
        UserUpdateProfileVO userUpdateProfileVO = userProfileService.updateCurrentUserProfile(dto);
        return Result.success(userUpdateProfileVO);
    }

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
//     * 查看本人手机号（明文）
//     */
//    @GetMapping("/phone")
//    @AuditLog(
//            moudle = AuditModule.SENSITIVE,
//            operation = AuditOperation.USER_VIEW_PHONE_SELF,
//            saveRequestData = false,
//            saveResponseData = false
//    )
//    public Result<String> myPhone() {
//        return Result.success(userProfileService.viewCurrentUserPlainPhone());
//    }
//
//    /**
//     * 查看本人邮箱（明文）
//     */
//    @GetMapping("/email")
//    @AuditLog(
//            moudle = AuditModule.SENSITIVE,
//            operation = AuditOperation.USER_VIEW_EMAIL_SELF,
//            saveRequestData = false,
//            saveResponseData = false
//    )
//    public Result<String> myEmail() {
//        return Result.success(userProfileService.viewCurrentUserPlainEmail());
//    }
//
//    /**
//     * 查看本人密码（明文/脱敏）
//     */
//    @GetMapping("/password")
//    @AuditLog(
//            moudle = AuditModule.SENSITIVE,
//            operation = AuditOperation.USER_VIEW_PASSWORD_SELF,
//            saveRequestData = false,
//            saveResponseData = false
//    )
//    public Result<String> myPassword() {
//        return Result.success(userProfileService.viewCurrentUserPlainPassword());
//    }
//
    /* ================= 手机号绑定 / 更换 ================= */
//
//    /**
//     * 初次绑定手机号（注册后可选）
//     */
//    @PostMapping("/phone")
//    @AuditLog(
//            module = AuditModule.USER,
//            operation = AuditOperation.USER_CREATE,
//            saveRequestData = false
//    )
//    public Result<Void> bindPhone(@Valid @RequestBody BindPhoneDTO dto) {
//        userProfileService.bindPhone(dto);
//        return Result.success();
//    }
//
//    /**
//     * 更换已绑定手机号
//     */
//    @PutMapping("/phone")
//    @AuditLog(
//            module = AuditModule.USER,
//            operation = AuditOperation.USER_UPDATE,
//            saveRequestData = false
//    )
//    public Result<Void> updatePhone(@Valid @RequestBody UpdatePhoneDTO dto) {
//        userProfileService.updatePhone(dto);
//        return Result.success();
//    }
//
//    /* ================= 邮箱绑定 / 更换 ================= */
//

//
//    /**
//     * 更换邮箱
//     */
//    @PutMapping("/email")
//    @AuditLog(
//            module = AuditModule.USER,
//            operation = AuditOperation.USER_UPDATE,
//            saveRequestData = false
//    )
//    public Result<Void> updateEmail(@Valid @RequestBody UpdateEmailDTO dto) {
//        userProfileService.updateEmail(dto);
//        return Result.success();
//    }
//

}