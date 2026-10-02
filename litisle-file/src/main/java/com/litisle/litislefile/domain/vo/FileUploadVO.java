package com.litisle.litislefile.domain.vo;

import com.litisle.litislefile.enums.SafeFlagEnum;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 文件上传返回 VO（单传/批量通用）
 * <p>秒传命中时 deduplicated=true，不落 SeaweedFS，直接复用已有 fileId</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * SeaweedFS 文件ID（业务主键，业务方持久化到各自业务表）
     */
    private String fileId;

    /**
     * 访问 URL：PUBLIC→公开直链；PRIVATE→预签名短时效 URL（30 分钟）
     */
    private String url;

    /**
     * SHA256 摘要（前端可用于完整性校验）
     */
    private String sha256;

    /**
     * MIME 类型
     */
    private String mime;

    /**
     * 文件大小（字节）
     */
    private Long size;

    /**
     * 图片宽度（非图片为 null）
     */
    private Integer width;

    /**
     * 图片高度（非图片为 null）
     */
    private Integer height;

    /**
     * 是否秒传命中（true=复用已有文件，未实际传输）
     */
    private Boolean deduplicated;

    /**
     * 违规审核状态：PENDING(2)=送审中（前端轮询 meta 接口直至终态），NORMAL(1)=可展示
     */
    private SafeFlagEnum safeFlag;
}
