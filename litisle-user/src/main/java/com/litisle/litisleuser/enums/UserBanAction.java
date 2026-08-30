package com.litisle.litisleuser.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 封号/解封操作类型
 */
@Getter
@AllArgsConstructor
public enum UserBanAction {

    BAN("BAN", "封号"),
    UNBAN("UNBAN", "解封");

    @EnumValue
    private final String code;
    private final String desc;
}