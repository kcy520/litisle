package com.litisle.litislecontent.domain.dto;

import com.litisle.commoncore.query.PageQuery;
import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 作者侧"我的文章"查询条件（GET /article/my 专用）
 * <p>继承 PageQuery：pageNo/pageSize/sortBy/isAsc 已内置规范化（防恶意大页）；
 * 排序走 sortBy 白名单（登录端点，白名单内开放自定义排序）</p>
 * <p>口径约束：authorId=当前登录人是 Service 硬条件（不占参数位、不可伪造）；
 * 状态筛选三态：status 细粒度单值优先 → published 二值（true=已发布 / false=未发布）
 * → 都不传=全部状态</p>
 *
 * @author litisle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleMyQueryDTO extends PageQuery {

    /** 分区筛选（可空=全部） */
    private ZoneEnum zone;

    /** 分类筛选（可空=全部；枚举反序列化即白名单校验，脏值 400） */
    private ArticleCategoryEnum category;

    /** 标题关键词（模糊匹配，可空；作者侧找回文章用） */
    private String keyword;

    /** 状态细粒度筛选（可空；与 published 同传时本字段优先）：
     * 0=建档草稿态（攒批送审挑篇目）/ 1=待审 / 2=已发布 / 3=驳回 / 4=下架 */
    private ArticleStatusEnum status;

    /** 发布二值筛选（可空）：true=已发布(status=2)；false=未发布（0/1/3/4 全部圈住）；
     * 前端"我的文章"页三个 tab（全部/已发布/未发布）直接映射本字段 */
    private Boolean published;
}
