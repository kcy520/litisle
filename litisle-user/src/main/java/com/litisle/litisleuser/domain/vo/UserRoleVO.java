package com.litisle.litisleuser.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户侧角色 VO
 * <p>
 * 用于 /me 接口，返回当前用户拥有的角色列表。
 * 只暴露用户需要看到的信息，不包含层级等内部概念。
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class UserRoleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色编码（如 AUTHOR）
     * 前端一定需要拿 code 做判断，哪怕用户看不到它。
     * */
    private String code;

    /** 角色显示名称（如 "作者"） */
    private String name;

    /** 角色描述 */
    private String description;
}