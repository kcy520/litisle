package com.litisle.litisleuser.domain.vo;

import com.litisle.commonsecurity.enums.RoleLevel;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 管理侧角色 VO
 * <p>
 * 用于角色枚举列表接口、后台查看用户角色。
 * 包含层级信息，供前端做分组展示与权限控制。
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class RoleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色编码（如 AUTHOR） */
    private String code;

    /** 角色显示名称（如 "作者"） */
    private String name;

//    /** 角色描述 */
//    private String description;

    /** 角色层级（如 BUSINESS） */
    private RoleLevel level;
}