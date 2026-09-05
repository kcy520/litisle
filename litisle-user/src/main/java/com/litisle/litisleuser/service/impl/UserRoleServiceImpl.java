package com.litisle.litisleuser.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.commonsecurity.enums.RoleCodeEnum;
import com.litisle.litisleuser.domain.entity.Role;
import com.litisle.litisleuser.domain.entity.User;
import com.litisle.litisleuser.domain.entity.UserRole;
import com.litisle.litisleuser.domain.vo.RoleVO;
import com.litisle.litisleuser.mapper.RoleMapper;
import com.litisle.litisleuser.mapper.UserRoleMapper;
import com.litisle.litisleuser.service.RoleService;
import com.litisle.litisleuser.service.UserRoleService;
import com.litisle.litisleuser.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class UserRoleServiceImpl
        extends ServiceImpl<UserRoleMapper, UserRole>
        implements UserRoleService {

    private final UserService userService;
    private final RoleMapper roleMapper ;



    /**
     * 查询角色集合（角色枚举/字典）
     * 角色定义的唯一来源是 RoleCodeEnum，不依赖数据库
     */
    @Override
    public List<RoleVO> listRoles() {

        return Arrays.stream(RoleCodeEnum.values())
//                .filter(role -> role != RoleCodeEnum.SUPER_ADMIN) // 不暴露超管给前端
                .map(role -> new RoleVO()
                        .setCode(role.getCode())
                        .setName(role.getDesc())
                        .setLevel(role.getLevel())
                )
                .toList();
    }

    /**
     * 查询当前用户拥有角色
     * @return
     */
    /**
     * 查询当前用户拥有角色
     */
    @Override
    public List<RoleVO> currentUserRoles() {

        // 1. 从 SaToken 取当前登录用户 ID
        Long userId = StpUtil.getLoginIdAsLong();

        // 2. 复用已有的 getRoleCodes 逻辑
        List<String> roleCodes = getRoleCodes(userId);

        if (roleCodes.isEmpty()) {
            return List.of();
        }

        // 3. 通过 RoleCodeEnum 转成 RoleVO
        List<RoleVO> list = roleCodes.stream()
                .map(RoleCodeEnum::of)
                .filter(Objects::nonNull)
                .map(role -> new RoleVO()
                        .setCode(role.getCode())
                        .setName(role.getDesc())
                        .setLevel(role.getLevel())
                )
                .toList();

        return list;
    }

    /**
     * 聚合用户角色编码列表
     * <p>
     * 复用逻辑：后台用户管理（列表/详情/等级校验/审计快照）与前台当前用户角色查询均依赖，
     * 抽取到 UserRoleService 统一维护，避免多处重复实现。
     */
    @Override
    public List<String> getRoleCodes(Long userId) {
        // 1. 查询用户-角色关联
        List<UserRole> userRoles = lambdaQuery()
                .eq(UserRole::getUserId, userId)
                .list();
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        // 2. 一次 IN 查询取出所有角色，避免循环内逐条 getById（N+1）
        List<Long> roleIds = userRoles.stream()
                .map(UserRole::getRoleId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Role> roleMap = roleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(Role::getId, Function.identity()));
        return userRoles.stream()
                .map(ur -> roleMap.get(ur.getRoleId()))
                .filter(Objects::nonNull)
                .map(Role::getRoleCode)
                .collect(Collectors.toList());
    }

    /**
     * 根据id查询用户角色
     * @param userId
     * @return
     */
    @Override
    public List<RoleVO> userRoles(Long userId) {

        //userRoles() 这个接口本身不应该做“用户存在 / 注销”校验。
        //查角色是「只读查询」，不是「业务写操作」，不应该为一次只读查询额外承担用户生命周期校验的成本。

        //查询此用户对应角色
        List<String> roleCodes = getRoleCodes(userId);

        if (roleCodes.isEmpty()) {
            return List.of();
        }

        // 2. 通过 RoleCodeEnum 转成 RoleVO
        List<RoleVO> list = roleCodes.stream()
                .map(RoleCodeEnum::of)
                .filter(Objects::nonNull)
                .map(role -> new RoleVO()
                        .setCode(role.getCode())
                        .setName(role.getDesc())
                        .setLevel(role.getLevel())
                )
                .toList();


        return list;
    }

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