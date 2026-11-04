package com.litisle.litisleapi.dto.content;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 热门文章轻量投影（搜索 ES 不可用降级链路的数据源，批次八 8.1）
 *
 * 【使用场景】
 * search 检测到 ES 整体不可用且 `litisle.search.fallback.enabled=true` 时，
 * 通过 ContentClient#getHotArticles 取内容服务热门榜，组装 source=DEGRADED 的检索结果。
 *
 * 【字段口径】
 * - 只保留检索结果卡片渲染所需的最小字段，不含正文、封面等大字段（降级链路同样遵守列表不取大字段纪律）
 * - publishTime 映射检索契约 ArticleDocVO.updateTime，前端无需区分降级来源的字段差异
 *
 * @author litisle
 */
@Data
public class HotArticleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章 ID（检索结果卡片跳转依据） */
    private Long articleId;

    /** 标题 */
    private String title;

    /** 摘要（列表卡片展示，空值时前端只展示标题） */
    private String summary;

    /** 作者用户 ID（作者昵称由前端/user 服务聚合，降级链路不再远程补齐） */
    private Long authorId;

    /** 发布时间（映射检索契约 ArticleDocVO.updateTime 展示） */
    private LocalDateTime publishTime;
}
