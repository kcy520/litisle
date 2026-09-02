package com.litisle.litisleuser.utils;

import com.litisle.commonsecurity.enums.RoleCodeEnum;
import com.litisle.litisleuser.domain.entity.Role;
import com.litisle.litisleuser.domain.entity.UserRole;
import com.litisle.litisleuser.service.RoleService;
import com.litisle.litisleuser.service.UserRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色工具类
 * <p>
 * 基于 DB 聚合目标用户的角色编码，用于判断目标用户是否拥有某类角色。
 * 与 SaRoleHelper（基于当前登录态）不同，本类面向任意目标用户。
 */
@Component
@RequiredArgsConstructor
public class RoleUtils {

    private final UserRoleService userRoleService;
    private final RoleService roleService;

    /**
     * 判断目标用户是否拥有作者角色（批量查询角色编码，避免 N+1）
     *
     * @param userId 目标用户ID
     * @return true=拥有作者角色
     */
    public boolean isAuthor(Long userId) {
        List<UserRole> userRoles = userRoleService.lambdaQuery()
                .eq(UserRole::getUserId, userId)
                .list();
        if (userRoles.isEmpty()) {
            return false;
        }
        List<Long> roleIds = userRoles.stream()
                .map(UserRole::getRoleId)
                .collect(Collectors.toList());
        return roleService.lambdaQuery()
                .in(Role::getId, roleIds)
                .list()
                .stream()
                .map(Role::getRoleCode)
                .anyMatch(code -> RoleCodeEnum.AUTHOR.getCode().equals(code)
                        || RoleCodeEnum.LEGACY_AUTHOR.getCode().equals(code));
    }
}
