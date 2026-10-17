package com.litisle.litislecontent.domain.dto;

import com.litisle.commoncore.query.PageQuery;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文章列表分页查询条件（公开浏览 + 作者侧"我的文章"通用）
 * <p>继承 PageQuery：pageNo/pageSize/sortBy/isAsc 已内置规范化（防恶意大页）</p>
 * <p>口径约束：匿名公开列表业务层强制 status=PUBLISHED + visibility=PUBLIC（不接受前端传状态）；
 * 作者侧按 authorId=当前登录人 + 可选 status 过滤</p>
 *
 * @author litisle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticleQueryDTO extends PageQuery {

    /** 分区筛选（可空=全部；枚举已实现 IntDictEnum，name/code/desc 三值兼容绑定） */
    private ZoneEnum zone;

    /** 分类筛选（可空=全部） */
    private String category;

    /** 标题关键词（模糊匹配，可空） */
    private String keyword;

    /** 状态筛选（仅作者侧生效；公开列表业务层强制 PUBLISHED） */
    private ArticleStatusEnum status;
}