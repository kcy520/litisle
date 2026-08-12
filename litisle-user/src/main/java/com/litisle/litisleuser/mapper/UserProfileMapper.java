package com.litisle.litisleuser.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litisleuser.domain.entity.UserProfile;
import com.litisle.litisleuser.domain.vo.UserUpdateProfileVO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 更新个人信息(作者)
     * @param userId
     * @return
     */
    UserUpdateProfileVO selectUpdateProfileByUserId(Long userId);

    /**
     * 更新个人信息（非作者）
     * @param userId
     * @return
     */
    UserUpdateProfileVO selectUpdateProfileBaseByUserId(Long userId);

}
