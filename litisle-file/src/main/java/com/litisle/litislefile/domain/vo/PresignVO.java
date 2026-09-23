package com.litisle.litislefile.domain.vo;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 预签名 URL VO（POST /{fileId}/presign 强制签发）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresignVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * SeaweedFS 文件ID
     */
    private String fileId;

    /**
     * 预签名短时效 URL
     */
    private String url;

    /**
     * URL 过期时间（now + expireMinutes，前端可据此提前刷新）
     */
    private LocalDateTime expireTime;
}
