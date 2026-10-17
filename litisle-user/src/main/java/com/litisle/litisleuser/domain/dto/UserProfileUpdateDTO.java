package com.litisle.litisleuser.domain.dto;

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
     * 用户名（放宽口径：允许中文等任意可见字符，仅保留安全校验 + 非空白约束）
     * 安全校验：① 长度上限 32；② 禁控制字符与 <>&"' 等注入/XSS 敏感字符；③ 传入时不得为空串/纯空白
     * （字段整体可空＝不修改；一旦传入则必须满足上述约束）
     */
    @Size(max = 32, message = "用户名长度不能超过 32 个字符")
    @Pattern(regexp = "^(?=.*\\S)[^\\p{Cntrl}<>&\"']*$", message = "用户名不能为空或纯空白，且不能包含控制字符或 <>&\"' 等敏感字符")
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

}