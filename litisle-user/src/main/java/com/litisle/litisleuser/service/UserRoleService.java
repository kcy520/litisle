package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleuser.domain.entity.UserRole;
import com.litisle.litisleuser.domain.vo.RoleVO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(rollbackFor = Exception.class)
public interface UserRoleService extends IService<UserRole> {
    /**
     * 后台查询用户角色集合
     * @return
     */
    List<RoleVO> listRoles();

//    List<String> getRoleCodesByUserId(Long userId);
}