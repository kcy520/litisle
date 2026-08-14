package com.litisle.commonsecurity.enums;

public enum RoleLevel {

    /**
     * 普通用户层：READER
     */
    NORMAL,

    /**
     * 业务角色层：AUTHOR / LEGACY_AUTHOR
     */
    BUSINESS,

    /**
     * 审核层：REVIEWER（后台干活的人）
     */
    REVIEWER,

    /**
     * 管理角色层：ADMIN
     */
    ADMIN,

    /**
     * 超级管理员：SUPER_ADMIN（测试/最高权限）
     */
    SUPER_ADMIN
}