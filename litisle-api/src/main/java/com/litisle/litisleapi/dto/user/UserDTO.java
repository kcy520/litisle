package com.litisle.litisleapi.dto.user;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户基本信息 DTO
 */
@Data
public class UserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    @NotNull
    private Long userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像 fileId
     */
    private String avatarFileId;

    /**
     * 状态（0-正常 1-禁用）
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}