package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

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

    /** 分区（1-普通 / 2-作家；批次十 10.1 新增，前端可按分区筛选展示） */
    private ZoneEnum zone;

    /** 分类（批次十 10.1 新增，前端可展示分类标签） */
    private ArticleCategoryEnum category;

    /**
     * 标签（批次十 10.1 新增，字段先留位）
     * <p>P0 恒为 null：已确认决策要求 P0 不读 article_tag，P1 启用标签偏好后才填充</p>
     */
    private List<String> tags;
}