package com.litisle.commonsecurity.utils;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.enums.RoleCodeEnum;
import com.litisle.commonsecurity.enums.RoleLevel;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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

    public static boolean isReviewer() {
        return StpUtil.hasRole(RoleCodeEnum.REVIEWER.getCode());
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

    /* ================= 越权校验（管理操作） ================= */

    /**
     * 计算一组角色编码中的最高等级（用于越权校验）
     */
    public static RoleLevel maxRoleLevel(List<String> roleCodes) {
        return roleCodes.stream()
                .map(RoleCodeEnum::of)
                .filter(Objects::nonNull)
                .map(RoleCodeEnum::getLevel)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(RoleLevel.NORMAL);
    }

    /**
     * 越权校验：禁止对同级或更高等级的目标用户执行管理操作（防止越权提权）
     *
     * @param targetRoleCodes 目标用户角色编码集合（由调用方从 DB 聚合传入）
     */
    public static void checkNotSameOrHigherLevel(List<String> targetRoleCodes) {
        RoleLevel operatorLevel = maxRoleLevel(StpUtil.getRoleList());
        RoleLevel targetLevel = maxRoleLevel(targetRoleCodes);
        if (targetLevel.ordinal() >= operatorLevel.ordinal()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }
}