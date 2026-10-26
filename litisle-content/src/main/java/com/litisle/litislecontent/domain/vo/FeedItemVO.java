package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 推荐流条目 VO（预计算推荐链路出参，zone 1/2 聚合视图；展示前已回查剔除下架/转私密内容）
 * <p>不带 content_html——点击进详情页才拉正文（缓存体积与 RT 考量）</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class FeedItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章ID */
    private Long articleId;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 封面文件ID */
    private String coverFileId;

    /** 作者用户ID */
    private Long authorId;

    /** 浏览量 */
    private Long viewCnt;

    /** 点赞数 */
    private Long likeCnt;

    /** 评论数（对齐 Card/Detail VO 三计数口径） */
    private Long commentCnt;

    /** 发布时间 */
    private LocalDateTime publishTime;
}