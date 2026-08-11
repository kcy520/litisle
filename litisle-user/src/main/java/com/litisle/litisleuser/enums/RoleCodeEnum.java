package com.litisle.litisleuser.enums;


import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;




/**
 * 用户角色枚举
 * <p>
 * 规则说明：
 * 1. READER 是基础角色，所有人默认拥有
 * 2. AUTHOR 与 LEGACY_AUTHOR 互斥（二选一）
 * 3. REVIEWER 属于后台审核角色
 * 4. ADMIN 是后台管理角色
 * 5. SUPER_ADMIN 仅用于测试/最高权限，不与其他角色叠加
 * 6. 业务角色（AUTHOR/LEGACY_AUTHOR）与管理角色（ADMIN/REVIEWER）不叠加
 *
 * @author system
 */
@Getter
@AllArgsConstructor
public enum RoleCodeEnum {

    // ========== 普通层 ==========
    READER("READER", "读者", RoleLevel.NORMAL),

    // ========== 业务层 ==========
    AUTHOR("AUTHOR", "作者", RoleLevel.BUSINESS),
    LEGACY_AUTHOR("LEGACY_AUTHOR", "纪念作者", RoleLevel.BUSINESS),

    // ========== 审核层 ==========
    REVIEWER("REVIEWER", "审核员", RoleLevel.REVIEWER),

    // ========== 管理层 ==========
    ADMIN("ADMIN", "管理员", RoleLevel.ADMIN),

    // ========== 超管层 ==========
    SUPER_ADMIN("SUPER_ADMIN", "超级管理员", RoleLevel.SUPER_ADMIN);

    private final String code;
    private final String desc;
    private final RoleLevel level;

    // ===================== 缓存 =====================

    // 类加载时构建 code → enum 映射，避免重复 stream，提升性能
    private static final Map<String, RoleCodeEnum> CODE_MAP =
            Arrays.stream(values()).collect(Collectors.toMap(RoleCodeEnum::getCode, e -> e));

    /**
     * 根据 code 获取枚举
     * 常用于 DB 反序列化、Feign 参数转换
     */
    public static RoleCodeEnum of(String code) {
        return CODE_MAP.get(code);
    }

    /**
     * 判断 code 是否合法
     * 常用于接口入参校验，防止脏数据
     */
    public static boolean exists(String code) {
        return CODE_MAP.containsKey(code);
    }

    // ===================== 角色属性判断 =====================

    /**
     * 是否为普通角色（基础读者）
     */
    public boolean isNormalRole() {
        return this.level == RoleLevel.NORMAL;
    }

    /**
     * 是否为业务角色（作者类）
     */
    public boolean isBusinessRole() {
        return this.level == RoleLevel.BUSINESS;
    }

    /**
     * 是否为审核角色（后台）
     */
    public boolean isReviewerRole() {
        return this.level == RoleLevel.REVIEWER;
    }

    /**
     * 是否为管理角色（后台）
     */
    public boolean isAdminRole() {
        return this.level == RoleLevel.ADMIN;
    }

    /**
     * 是否为超级管理员
     */
    public boolean isSuperAdmin() {
        return this == SUPER_ADMIN;
    }

    /**
     * 是否为后台角色（审核/管理/超管）
     */
    public boolean isBackendRole() {
        return this.level.ordinal() >= RoleLevel.REVIEWER.ordinal();
    }

    // ===================== 角色间关系判断 =====================

    /**
     * 与另一个角色是否互斥
     */
    public boolean isMutualExclusiveWith(RoleCodeEnum other) {
        if (other == null || other == this) {
            return false;
        }
        // AUTHOR ↔ LEGACY_AUTHOR 互斥
        if ((this == AUTHOR && other == LEGACY_AUTHOR)
                || (this == LEGACY_AUTHOR && other == AUTHOR)) {
            return true;
        }
        // 业务角色与管理/审核角色互斥
        if (this.isBusinessRole() && other.isBackendRole()) {
            return true;
        }
        if (this.isBackendRole() && other.isBusinessRole()) {
            return true;
        }
        // 审核员与管理员互斥
        if ((this == REVIEWER && other.isAdminRole())
                || (this.isAdminRole() && other == REVIEWER)) {
            return true;
        }
        // 超管不与其他任何角色叠加
        if (this.isSuperAdmin() || other.isSuperAdmin()) {
            return true;
        }
        return false;
    }

    /**
     * 校验一组角色是否全部合法（无互斥）
     */
    public static boolean isValidRoleCombination(Set<RoleCodeEnum> roles) {
        if (roles == null || roles.isEmpty()) {
            return false;
        }
        // 至少包含 READER
        boolean hasReader = roles.contains(READER);
        if (!hasReader) {
            return false;
        }
        // 互斥校验
        RoleCodeEnum[] array = roles.toArray(new RoleCodeEnum[0]);
        for (int i = 0; i < array.length; i++) {
            for (int j = i + 1; j < array.length; j++) {
                if (array[i].isMutualExclusiveWith(array[j])) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 获取用户的有效层级（取最高）
     */
    public static RoleLevel getEffectiveLevel(Set<RoleCodeEnum> roles) {
        return roles.stream()
                .map(RoleCodeEnum::getLevel)
                .max(RoleLevel::compareTo)
                .orElse(RoleLevel.NORMAL);
    }
}