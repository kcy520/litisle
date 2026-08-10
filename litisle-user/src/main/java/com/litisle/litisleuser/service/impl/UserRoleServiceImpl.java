package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.litisleuser.domain.entity.UserRole;
import com.litisle.litisleuser.mapper.UserRoleMapper;
import com.litisle.litisleuser.service.IUserRoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class UserRoleServiceImpl
        extends ServiceImpl<UserRoleMapper, UserRole>
        implements IUserRoleService {

    private final UserRoleMapper userRoleMapper;

//    @Override
//    public List<String> getRoleCodesByUserId(Long userId) {
//        return lambdaQuery()
//                .eq(UserRole::getUserId, userId)
//                .list()
//                .stream()
//                .map(UserRole::getRoleCode)
//                .collect(Collectors.toList());
//    }
}