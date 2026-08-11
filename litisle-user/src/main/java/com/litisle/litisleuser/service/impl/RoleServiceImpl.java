package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.litisleuser.domain.entity.Role;
import com.litisle.litisleuser.mapper.RoleMapper;
import com.litisle.litisleuser.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoleServiceImpl
        extends ServiceImpl<RoleMapper, Role>
        implements RoleService {

    private final RoleMapper roleMapper;

    @Override
    public Role getByRoleCode(String roleCode) {
        return lambdaQuery()
                .eq(Role::getRoleCode, roleCode)
                .one();
    }
}