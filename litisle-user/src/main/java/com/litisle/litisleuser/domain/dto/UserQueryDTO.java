package com.litisle.litisleuser.domain.dto;

import com.litisle.commoncore.enums.UserStatus;
import com.litisle.commoncore.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 后台用户管理 - 分页查询条件
 *
 * @author litisle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryDTO extends PageQuery {

    /** 用户名（模糊匹配） */
    private String username;

    /** 对外UID（精确匹配） */
    private String uid;

    /** 用户状态筛选 */
    private UserStatus status;

    /** 角色编码筛选（如 AUTHOR） */
    private String roleCode;

    /** 注册时间下界 */
    private LocalDateTime startTime;

    /** 注册时间上界 */
    private LocalDateTime endTime;

    /**
     * 是否包含已逻辑删除的用户
     * <p>默认 false，仅查询未删除用户；为 true 时不过滤 del_flag，可查到已注销/已删除记录（管理员审计场景使用）。
     */
    private Boolean includeDeleted = false;
}
