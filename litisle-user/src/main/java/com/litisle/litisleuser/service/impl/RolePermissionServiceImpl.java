package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.litisleuser.domain.entity.RolePermission;
import com.litisle.litisleuser.mapper.RolePermissionMapper;
import com.litisle.litisleuser.service.IRolePermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RolePermissionServiceImpl
        extends ServiceImpl<RolePermissionMapper, RolePermission>
        implements IRolePermissionService {

    private final RolePermissionMapper rolePermissionMapper;
}