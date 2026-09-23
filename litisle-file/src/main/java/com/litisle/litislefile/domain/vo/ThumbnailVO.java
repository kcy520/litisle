package com.litisle.litislefile.domain.vo;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 缩略图 VO（POST /{fileId}/thumbnail）
 * <p>缩略图路径按约定派生自原图 path，不落库（File-Architecture.md §4.1 设计要点 5）</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThumbnailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * SeaweedFS 文件ID（原图）
     */
    private String fileId;

    /**
     * 缩略图访问 URL（公开直链）
     */
    private String url;

    /**
     * 缩略图宽度
     */
    private Integer width;

    /**
     * 缩略图高度
     */
    private Integer height;
}
