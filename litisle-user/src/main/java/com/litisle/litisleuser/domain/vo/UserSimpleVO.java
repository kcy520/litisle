package com.litisle.litisleuser.domain.vo;

import com.litisle.litisleuser.enums.AvatarColor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 精简用户 VO（列表 / 评论 / Feign 调用）
 *
 * 【使用场景】
 * 1. 文章作者信息回显
 * 2. 评论列表用户信息
 * 3. 搜索结果用户简略信息
 * 4. Feign 接口返回（高频调用）
 *
 * 【设计说明】
 * 1. 字段极简，避免 JOIN sys_user_profile
 * 2. 仅从 sys_user 表即可组装
 * 3. 适用于高并发、低延迟场景
 * 4. 不允许扩展业务字段（避免滥用）
 *
 * @author litisle
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserSimpleVO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 对外UID（用户间搜索唯一标识）
     */
    private String uid;

    /**
     * 用户名
     */
    private String username;

    /**
     * 头像 URL（已拼接完整访问地址）
     */
    private String avatar;

    /**
     * 头像标识
     * 0-无 1-金色（作家） 2-白色（纪念作家）
     */
    private AvatarColor avatarColor;
}