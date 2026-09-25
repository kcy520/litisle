package com.litisle.litisleapi.dto.file;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 文件引用登记 DTO（bindRef/unbindRef 跨服务入参，方案C）
 *
 * 【使用场景】
 * 业务服务（如 user 设置头像）通过 FileClient 与 file 服务登记/解除 file_ref 引用行，
 * 与业务表同事务（Seata AT 接入后），防秒传共享文件被误删后其他复用方悬空（见 next_update.md P0）
 *
 * 【设计说明】
 * 1. bizType 用 String 而非枚举：BizTypeEnum 定义在 litisle-file 模块，
 *    litisle-api 不能反向依赖（会成环），由提供方解析（DictEnum 三值兼容）
 * 2. 四元组 fileId+bizType+bizId+ownerId 即 file_ref 唯一定位，幂等安全
 *
 * @author litisle
 */
@Data
public class FileRefDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 引用的文件ID（file_meta.file_id） */
    @NotBlank(message = "fileId 不能为空")
    private String fileId;

    /** 引用方业务类型：AVATAR / COVER / ARTICLE_IMG / LICENSE（兼容枚举名与 code） */
    @NotBlank(message = "bizType 不能为空")
    private String bizType;

    /** 引用方业务主键（换头像场景为 sys_user.id） */
    @NotNull(message = "bizId 不能为空")
    private Long bizId;

    /** 引用归属用户ID（审计与授权依据；服务间调用方传入，不可信任外部请求） */
    @NotNull(message = "ownerId 不能为空")
    private Long ownerId;
}
