package com.litisle.commonsecurity.utils;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commonsecurity.enums.RoleCodeEnum;

public class SaRoleHelper {

    /* ================= 判断（不抛异常） ================= */

    public static boolean isAuthor() {
        return StpUtil.hasRoleOr(
                RoleCodeEnum.AUTHOR.getCode(),
                RoleCodeEnum.LEGACY_AUTHOR.getCode()
        );
    }

    public static boolean isAdmin() {
        return StpUtil.hasRoleOr(
                RoleCodeEnum.ADMIN.getCode(),
                RoleCodeEnum.SUPER_ADMIN.getCode()
        );
    }

    /* ================= 校验（抛异常） ================= */

    public static void checkAuthor() {
        StpUtil.checkRoleOr(
                RoleCodeEnum.AUTHOR.getCode(),
                RoleCodeEnum.LEGACY_AUTHOR.getCode()
        );
    }

    public static void checkAdmin() {
        StpUtil.checkRoleOr(
                RoleCodeEnum.ADMIN.getCode(),
                RoleCodeEnum.SUPER_ADMIN.getCode()
        );
    }
}