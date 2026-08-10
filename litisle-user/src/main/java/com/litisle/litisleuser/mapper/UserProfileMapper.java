package com.litisle.litisleuser.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litisleuser.domain.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}
