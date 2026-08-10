package com.litisle.litisleuser.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litisleuser.domain.entity.OAuthBinding;
import com.litisle.litisleuser.domain.entity.Permission;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OAuthBindingMapper extends BaseMapper<OAuthBinding> {
}
