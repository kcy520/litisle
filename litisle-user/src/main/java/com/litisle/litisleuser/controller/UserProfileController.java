package com.litisle.litisleuser.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.litisle.commoncore.enums.AuditModule;
import com.litisle.commoncore.enums.AuditOperation;
import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.audit.AuditLog;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litisleuser.constant.UserLimitConstant;
import com.litisle.litisleuser.domain.dto.AvatarUpdateDTO;
import com.litisle.litisleuser.domain.dto.BindEmailDTO;
import com.litisle.litisleuser.domain.dto.UpdateEmailDTO;
import com.litisle.litisleuser.domain.dto.UserProfileUpdateDTO;
import com.litisle.litisleuser.domain.vo.UserPublicVO;
import com.litisle.litisleuser.domain.vo.UserSelfVO;
import com.litisle.litisleuser.domain.vo.UserUpdateProfileVO;
import com.litisle.litisleuser.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

/**
 * 当前用户资料
 * 负责昵称、头像、手机号、邮箱、简介、笔名。
 * */

@Tag(name = "用户资料接口", description = "当前登录用户资料 / 公开资料 / 修改基础资料")
@RestController
@RequestMapping("/api/user/profile")
@SaCheckLogin//登录校验
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /**
     * 获取当前登录用户资料
     */
    @Operation(summary = "获取当前登录用户资料")
    @GetMapping("/me")
    public Result<UserSelfVO> me() {
        return Result.success(userProfileService.currentUserProfile());
    }

    /**
     * 根据 ID 获取公开资料
     */
    @Operation(summary = "根据 ID 获取公开资料")
    @GetMapping("/{userId}")
    public Result<UserPublicVO> publicProfile(@PathVariable Long userId) {
        return Result.success(userProfileService.publicProfile(userId));
    }

    /**
     * 修改基础资料
     */
    @Operation(summary = "修改基础资料")
    @PutMapping
    public Result<UserUpdateProfileVO> update(@Valid @RequestBody UserProfileUpdateDTO dto) {
        UserUpdateProfileVO userUpdateProfileVO = userProfileService.updateCurrentUserProfile(dto);
        return Result.success(userUpdateProfileVO);
    }

    /**
     * 设置头像（P0：file_ref 引用登记链路）
     * <p>文件上传走文件服务（POST /api/file/upload，bizType=AVATAR），本接口凭返回的 fileId 绑定头像；
     * 服务层完成归属校验 + 旧头像解绑 + 新头像绑定引用 + 更新 sys_user.avatar_file_id
     */
    @Operation(summary = "设置头像", description = "凭上传接口返回的 fileId 绑定头像；仅限本人上传的 AVATAR 类型文件；旧头像自动解绑引用，防秒传共享文件被误删")
    @PostMapping("/avatar")
    public Result<Void> updateAvatar(@Valid @RequestBody AvatarUpdateDTO dto) {
        //① 前端 → POST /api/file/upload  (multipart: file, bizType=AVATAR)
        //        → 文件服务校验/秒传/落 SeaweedFS，返回 { fileId, deduplicated }
        //
        //② 前端 → POST /api/user/profile/avatar  (JSON: { avatarFileId: "<fileId>" })
        //        → 归属校验 + 旧头像解绑 + 新头像绑定引用 + 回写 sys_user.avatar_file_id
        userProfileService.updateAvatar(dto);
        return Result.success();
    }

//    ---------建议删除此注释（上方真实接口已取代本占位代码）
//    /**
//     * 上传/更新头像
//     * 文件实际上传可以到文件服务，user 模块只保存 SeaweedFS 返回的 file id/url
//     */
//    @PostMapping("/avatar")
//    public Result<AvatarVO> updateAvatar(@Valid @RequestBody AvatarUpdateDTO dto) {
//        return Result.success(userProfileService.updateAvatar(dto));
//    }



    /* ================= 手机号绑定 / 更换 ================= */


//    /**
//     * 更换已绑定手机号
//     */
//    @PutMapping("/phone")
//    @AuditLog(
//            moudle =  AuditModule.USER,
//            operation = AuditOperation.USER_UPDATE,
//            saveRequestData = false
//    )
//    public Result<Void> updatePhone(@Valid @RequestBody UpdatePhoneDTO dto) {
//        userProfileService.updatePhone(dto);
//        return Result.success();
//    }

    /* ================= 邮箱绑定 / 更换 ================= */

            //此类操作需要图像验证码+redis限流注解+邮箱验证码


    /**
     * 初次绑定邮箱（注册后可选）
     * 高危敏感操作：双码校验 + IP 维度限流（60 秒内 5 次，防撞库/防爆破）
     */
    @PostMapping("/email")
    @AuditLog(
            moudle =  AuditModule.USER,
            operation = AuditOperation.USER_CREATE,
            saveRequestData = false
    )
    @RateLimiter(time = UserLimitConstant.WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = UserLimitConstant.EMAIL_BIND_MAX, keyType = RateLimiter.KeyType.IP)
    public Result<Void> bindEmail(@Valid @RequestBody BindEmailDTO dto) {
        userProfileService.bindEmail(dto);
        return Result.success();
    }


    /**
     * 更换邮箱
     * 高危敏感操作：双码校验 + IP 维度限流（60 秒内 5 次，防撞库/防爆破）
     */
    @PutMapping("/email")
    @AuditLog(
            moudle =  AuditModule.USER,
            operation = AuditOperation.USER_UPDATE,
            saveRequestData = false
    )
    @RateLimiter(time = UserLimitConstant.WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = UserLimitConstant.EMAIL_BIND_MAX, keyType = RateLimiter.KeyType.IP)
    public Result<Void> updateEmail(@Valid @RequestBody UpdateEmailDTO dto) {
        userProfileService.updateEmail(dto);
        return Result.success();
    }


}