package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.litisle.litisleuser.domain.entity.Permission;
import com.litisle.litisleuser.mapper.PermissionMapper;
import com.litisle.litisleuser.service.IPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionServiceImpl
        extends ServiceImpl<PermissionMapper, Permission>
        implements IPermissionService {

    private final PermissionMapper sysPermissionMapper;
}