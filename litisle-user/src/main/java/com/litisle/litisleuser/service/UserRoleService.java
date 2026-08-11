package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleuser.domain.entity.UserRole;
import org.springframework.transaction.annotation.Transactional;

@Transactional(rollbackFor = Exception.class)
public interface UserRoleService extends IService<UserRole> {
//    List<String> getRoleCodesByUserId(Long userId);
}