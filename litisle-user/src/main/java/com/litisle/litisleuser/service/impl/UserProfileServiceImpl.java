package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.litisleuser.domain.entity.UserProfile;
import com.litisle.litisleuser.domain.vo.UserSelfVO;
import com.litisle.litisleuser.mapper.UserProfileMapper;
import com.litisle.litisleuser.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileServiceImpl
        extends ServiceImpl<UserProfileMapper, UserProfile>
        implements UserProfileService {

    private final UserProfileMapper userProfileMapper;

    /**
     * 获取当前登录用户资料
     *
     * @return
     */
    @Override
    public UserSelfVO currentUserProfile() {
        return null;
    }
}