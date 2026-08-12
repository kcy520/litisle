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
}
