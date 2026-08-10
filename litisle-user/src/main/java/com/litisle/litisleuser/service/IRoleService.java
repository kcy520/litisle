package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleuser.domain.entity.Role;

public interface IRoleService extends IService<com.litisle.litisleuser.domain.entity.Role> {
    Role getByRoleCode(String roleCode);
}