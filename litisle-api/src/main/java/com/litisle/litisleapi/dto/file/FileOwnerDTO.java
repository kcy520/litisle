package com.litisle.litisleapi.dto.file;

import lombok.Data;

import java.io.Serializable;

/**
 * 文件归属信息 DTO（服务间内部查询，不脱敏）
 *
 * 【使用场景】
 * 调用方设置引用前做归属校验（如 user 设置头像：file_meta.owner_id == 当前登录人，
 * 防挂他人文件）；区别于对外 meta 接口的"非 owner 脱敏 ownerId"——本接口仅服务间可用
 * （网关未路由 /api/feign/**），不存在泄露面
 *
 * @author litisle
 */
@Data
public class FileOwnerDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SeaweedFS 文件ID */
    private String fileId;

    /** 上传者ID（未脱敏，仅服务间使用） */
    private Long ownerId;

    /** 业务类型（DictEnum name，如 AVATAR） */
    private String bizType;

    /**
     * file_meta.id（数据库自增主键；file 侧提供方回填）
     * <p>review 侧图片审核落 moderation_record.biz_id / review_task.biz_id 用——biz_type=6 时
     * biz_id=file_meta.id（两套引用口径：biz_id 引用 file_meta.id，fileId 引用 file_meta.file_id 业务主键，
     * 见 ReviewSceneEnum.IMAGE / ModerationBizTypeEnum.FILE 注释）。提供方 TODO litisle-file：
     * GET /api/feign/file/{fileId}/info 需回填本字段</p>
     */
    private Long fileMetaId;
}
