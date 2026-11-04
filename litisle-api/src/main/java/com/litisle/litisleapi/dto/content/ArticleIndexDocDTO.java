package com.litisle.litisleapi.dto.content;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章索引回查 DTO（search 建索引专用，content 服务提供）。
 *
 * 【用途】search 消费 content.published / content.article-updated 事件后，
 * 按 articleId 回查建索引所需的全部字段（事件体只带定位键，禁止塞大字段）。
 *
 * 【字段口径】
 * - 正文 contentHtml 为入库净化后的 HTML（与发布送审同源）；文本化/切片由 search 侧负责
 * - status/visibility 返回枚举 code：status=2 且 visibility=1(PUBLIC) 才允许入索引
 * - publishTime 取文章 updateTime（content 无独立发布时间列；发布即更新，语义等价）
 * - likeCnt/commentCnt/viewCnt 为当前计数快照，供 search 初始化文章索引文档的热度基数
 *
 * @author litisle
 */
@Data
public class ArticleIndexDocDTO implements Serializable {

    /** 序列化版本号（跨服务传输） */
    private static final long serialVersionUID = 1L;

    /** 文章 ID（搜索结果主键，索引文档 id） */
    private Long articleId;

    /** 作者 ID（索引作者维度筛选字段） */
    private Long authorId;

    /** 作者昵称（可为空：用户服务不可用时不阻断建索引，作者名仅展示/联想用） */
    private String authorName;

    /** 标题（multi_match 最高权重字段） */
    private String title;

    /** 摘要（可为空；为空时索引侧保持 null，不影响检索） */
    private String summary;

    /** 正文 HTML（入库净化版；search 侧转纯文本后入全文检索字段与切片） */
    private String contentHtml;

    /** 标签名列表（标签池为 content 域数据，按 article_tag 关联查询） */
    private List<String> tags;

    /** 分类名（文章分类枚举的中文名，筛选维度） */
    private String category;

    /** 分区枚举 code（1-普通 2-作家，筛选维度） */
    private Integer zone;

    /** 文章状态枚举 code（1-待审核 2-已发布 3-未通过 4-已下架） */
    private Integer status;

    /** 可见性枚举 code（1-公开 2-仅自己 3-仅关注者） */
    private Integer visibility;

    /** 发布时间（取文章 updateTime） */
    private LocalDateTime publishTime;

    /** 点赞数快照（热度基数） */
    private Long likeCnt;

    /** 评论数快照（热度基数） */
    private Long commentCnt;

    /** 浏览数快照（热度基数） */
    private Long viewCnt;

    /** 最近一次送审文本指纹（与事件体比对留痕，防旧消息错配） */
    private String contentHash;
}
