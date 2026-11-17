package com.litisle.litisleapi.dto.ai;

import lombok.Data;

import java.io.Serializable;

/**
 * 索引期打标请求 DTO（板块八 1.3，search 索引链路 → ai 打标端点跨服务契约）
 *
 * <p>【口径】requestId 为计量幂等键组成（search 侧按 articleId 生成稳定键，重复调用不重复计费）；
 * title/content 为打标依据，content 由调用方（search）先截断到配置上限，防 prompt 放大。</p>
 *
 * @author litisle
 */
@Data
public class AiLabelRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求幂等键（search 侧按 articleId 生成，打标重试复用同一键） */
    private String requestId;

    /** 文章标题（打标主要依据之一） */
    private String title;

    /** 正文纯文本（调用方截断后传入，防超长 prompt） */
    private String content;
}
