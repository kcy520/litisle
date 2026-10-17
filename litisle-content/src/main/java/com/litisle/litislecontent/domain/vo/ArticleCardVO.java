package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.ZoneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章卡片 VO（列表页/热门列表/书籍章节目录通用）
 * <p>口径：不带 content_html 大字段——列表页延迟加载，详情页才取正文（防列表查询拖库）</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ArticleCardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章ID */
    private Long id;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 封面文件ID（前端经 file 服务换直链） */
    private String coverFileId;

    /** 分类 */
    private String category;

    /** 分区 */
    private ZoneEnum zone;

    /** 作者用户ID（作者昵称由 user 服务聚合，不冗余本表） */
    private Long authorId;

    /** 浏览量（冗余计数） */
    private Long viewCnt;

    /** 点赞数（冗余计数） */
    private Long likeCnt;

    /** 评论数（冗余计数） */
    private Long commentCnt;

    /** 发布时间（取 update_time 的发布时刻，业务层落库） */
    private LocalDateTime publishTime;
}