package com.litisle.litislefile.domain.vo;


import com.litisle.litislefile.enums.AccessLevelEnum;
import com.litisle.litislefile.enums.AlignEnum;
import com.litisle.litislefile.enums.BizTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件元数据 VO（GET /{fileId}/meta）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileMetaVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * SeaweedFS 文件ID（业务主键）
     */
    private String fileId;

    /**
     * 上传者ID
     */
    private Long ownerId;

    /**
     * 业务类型（AVATAR/COVER/ARTICLE_IMG/LICENSE）
     */
    private BizTypeEnum bizType;

    /**
     * 访问级别：PRIVATE(0) / PUBLIC(1)
     */
    private AccessLevelEnum access;

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
     * 对齐（文件维度冗余，权威来源在 content 库 article_image.align）
     */
    private AlignEnum align;

    /**
     * 上传时间
     */
    private LocalDateTime createTime;
}
