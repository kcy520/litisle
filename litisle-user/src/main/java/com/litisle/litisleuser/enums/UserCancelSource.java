package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 注销来源枚举
 * <p>
 * 区分"用户自助注销"与"管理员强制注销"，用于审计追溯责任方。
 */
@Getter
@AllArgsConstructor
public enum UserCancelSource {

    SELF("SELF", "用户自助注销"),
    ADMIN("ADMIN", "管理员强制注销");

    @EnumValue
    private final String code;
    private final String desc;
}
