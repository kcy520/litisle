package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleuser.domain.entity.UserProfile;
import com.litisle.litisleuser.domain.vo.UserSelfVO;

public interface UserProfileService extends IService<UserProfile> {

    /**
     * 获取当前登录用户资料
     *
     * @return
     */
    UserSelfVO currentUserProfile();
}
