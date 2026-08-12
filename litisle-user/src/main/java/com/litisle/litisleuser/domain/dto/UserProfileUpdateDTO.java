package com.litisle.litisleuser.domain.dto;

import com.litisle.litisleuser.enums.AvatarColor;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 当前登录用户修改基础资料 DTO
 *
 * 【使用场景】
 * 个人资料编辑页提交
 *
 * 【设计说明】
 * 1. 只改“允许用户自己改”的字段
 * 2. 用户名 / 手机号 / 状态 / 权限 等全部禁止出现
 * 3. 所有字段非必填（支持部分更新）
 */
@Data
public class UserProfileUpdateDTO {

    /**
     * 用户名
     */
    @Size(min = 3, max = 32, message = "用户名长度必须为 3-32 个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    /**
     * 个人简介
     */
    @Size(max = 200, message = "个人简介不能超过 200 个字符")
    private String bio;

    /**
     * 笔名（作家专用）
     */
    @Size(max = 32, message = "笔名不能超过 32 个字符")
    private String penName;

    /**
     * 创作方向
     */
    @Size(max = 100, message = "创作方向不能超过 100 个字符")
    private String creationDir;

    /**
     * 代表作简介
     */
    @Size(max = 500, message = "代表作简介不能超过 500 个字符")
    private String masterpiece;

    /**
     * 头像标识（0-无 1-金色 2-白色）
     * 实际头像 URL 由审核/上传接口单独管理
     */
    private AvatarColor avatarColor;
}