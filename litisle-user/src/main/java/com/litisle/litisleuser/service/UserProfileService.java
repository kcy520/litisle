package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleuser.domain.dto.UserProfileUpdateDTO;
import com.litisle.litisleuser.domain.entity.UserProfile;
import com.litisle.litisleuser.domain.vo.UserPublicVO;
import com.litisle.litisleuser.domain.vo.UserSelfVO;
import com.litisle.litisleuser.domain.vo.UserUpdateProfileVO;
import jakarta.validation.Valid;

public interface UserProfileService extends IService<UserProfile> {

    /**
     * 获取当前登录用户资料
     *
     * @return
     */
    UserSelfVO currentUserProfile();

    /**
     * 获取用户公开资料
     * @return
     */
    UserPublicVO publicProfile();

    /**
     * 用户资料修改
     * @param dto
     */
    UserUpdateProfileVO updateCurrentUserProfile(@Valid UserProfileUpdateDTO dto);

    /* ================= 本人敏感信息（明文） ================= */

    /**
     * 查看本人手机号（明文）
     * <p>
     * 仅用于本人身份校验，不落日志
     */
    String viewCurrentUserPlainPhone();

    /**
     * 查看本人邮箱（明文）
     */
    String viewCurrentUserPlainEmail();

//    /**
//     * 查看本人密码（明文/脱敏）
//     * <p>
//     * 仅用于前端二次校验、重设密码前置流程
//     */
//    String viewCurrentUserPlainPassword();

    /* ================= 管理员敏感信息（明文） ================= */

    /**
     * 管理员查看指定用户手机号
     *
     * @param userId 用户ID
     */
    String viewUserPlainPhone(Long userId);

    /**
     * 管理员查看指定用户邮箱
     *
     * @param userId 用户ID
     */
    String viewUserPlainEmail(Long userId);

//    /**
//     * 管理员查看指定用户密码
//     *
//     * @param userId 用户ID
//     */
//    String viewUserPlainPassword(Long userId);


}
