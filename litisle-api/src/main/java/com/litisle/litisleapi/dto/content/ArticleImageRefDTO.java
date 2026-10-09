package com.litisle.litisleapi.dto.content;


import lombok.Data;

import java.io.Serializable;

/**
 * 文章配图引用 DTO（文章配图复审 ARTICLE_IMG_RE_REVIEW 反查契约）
 *
 * 【使用场景】review 侧消费 moderation.image.submit（scene=ARTICLE_IMG_RE_REVIEW）后，
 * 经 ContentClient.listArticleImages(articleId) 反查该文章登记的 article_image 类型配图引用，
 * 逐张按 fileId 经 FileClient.getContent 内部字节流取图送审（严禁按用户 URL 抓取，SSRF 防线），
 * 单图结论落 moderation_record（biz_type=6、biz_id=file_meta.id）
 *
 * @author litisle
 */
@Data
public class ArticleImageRefDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SeaweedFS 文件ID（机审取图唯一入口 FileClient.getContent 的入参） */
    private String fileId;

    /** file_meta.id（moderation_record.biz_type=6 时的 biz_id 留痕口径） */
    private Long fileMetaId;
}
