package com.litisle.litisleapi.dto.user;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户索引回查 DTO（search 建 user_idx 索引用，user 服务提供）。
 *
 * 【用途】search 消费 user.registered / user.profile-updated 事件后按 userId 回查，
 * 组装 user_idx 索引文档所需的公开资料（笔名/简介）与状态门控字段。
 *
 * 【隐私纪律】只承载对外公开字段（笔名/简介），严禁把手机号/邮箱/密码等隐私字段放进本 DTO。
 *
 * 【字段口径】
 * - status 返回 UserStatus 的 code：0-禁用 1-正常 2-注销（仅正常状态入索引）
 * - delFlag 返回 DelFlag 的 code：0-未删除 1-已删除（已删除不入索引）
 *
 * @author litisle
 */
@Data
public class UserIndexDocDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（索引文档主键） */
    private Long userId;

    /** 笔名（作者对外身份，text + ik 检索字段；非作者或未设置为 null） */
    private String penName;

    /** 个人简介（text + ik 检索字段，可为空） */
    private String bio;

    /** 用户状态枚举 code（0-禁用 1-正常 2-注销） */
    private Integer status;

    /** 删除标志枚举 code（0-未删除 1-已删除） */
    private Integer delFlag;

    /** 资料更新时间（NEWEST 排序） */
    private LocalDateTime updateTime;
}
