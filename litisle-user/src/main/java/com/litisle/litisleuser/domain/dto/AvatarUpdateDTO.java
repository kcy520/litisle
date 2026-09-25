package com.litisle.litisleuser.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;


/**
 * 设置头像 DTO
 *
 * 【使用场景】
 * 前端先调文件服务上传（POST /api/file/upload，bizType=AVATAR）拿到 fileId，
 * 再调 POST /api/user/profile/avatar 完成绑定（归属校验 + 引用登记 + 回写 sys_user）
 *
 * @author litisle
 */
@Data
public class AvatarUpdateDTO {

    /** 头像文件ID（file_meta.file_id，上传接口返回） */
    @NotBlank(message = "头像文件ID不能为空")
    private String avatarFileId;
}