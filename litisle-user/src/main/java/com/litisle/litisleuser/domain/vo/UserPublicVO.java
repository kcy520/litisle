package com.litisle.litisleuser.domain.vo;

import com.litisle.litisleuser.enums.AvatarColor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 公共用户资料 VO（用户主页 / 作家主页）
 *
 * 【使用场景】
 * 1. 用户主页接口 /user/{id}
 * 2. 作家主页展示
 * 3. 对外公开信息（不可包含敏感字段）
 *
 * 【设计说明】
 * 1. 仅返回“可公开”的字段
 * 2. 不返回手机号、邮箱、VIP、封禁状态等敏感信息
 * 3. 头像返回完整 URL
 * 4. 适用于匿名 / 登录用户访问
 *
 * @author litisle
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserPublicVO {

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
     * 头像文件ID（过渡期透传 fileId；FileService 落地后由服务层拼接为 avatarUrl）
     */
    private String avatarFileId;

    /**
     * 个人简介
     */
    private String bio;

    /**
     * 笔名（作家专用，非作家可为 null）
     */
    private String penName;

    /**
     * 创作方向（作家专用）
     */
    private String creationDir;

    /**
     * 代表作简介（作家专用）
     */
    private String masterpiece;

    /**
     * 头像标识：无/金色（作家）/白色（纪念作家）（JSON 输出 desc，如"金色"）
     */
    private AvatarColor avatarColor;

    /**
     * 注册时间（仅日期维度，避免精确暴露）
     */
    private LocalDateTime createTime;
}