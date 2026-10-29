package com.litisle.litislecontent.domain.dto;

import com.litisle.commoncore.query.PageQuery;
import com.litisle.litislecontent.enums.BookStatusEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 作者侧"我的书籍"查询条件（GET /book/my 专用，P2 书籍发现入口 2026-10-28）
 * <p>继承 PageQuery：pageNo/pageSize 内置规范化兜底；排序走 sortBy 通用时间列白名单
 * （登录端点，白名单内开放自定义排序，白名单外回退默认 update_time 倒序）</p>
 * <p>口径约束：author_id=当前登录人 + del_flag=0（软删书不展示）为 Service 硬条件，
 * 复用文章 my 列表"归属不占参数位、不可伪造"模式</p>
 *
 * @author litisle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BookMyQueryDTO extends PageQuery {

    /** 分类筛选（可空=全部；自由字符串精确匹配，与 BookSaveDTO.category 同源） */
    private String category;

    /** 状态筛选（可空=全部）：连载中/已完结/下架（作者侧自查含下架书） */
    private BookStatusEnum status;

    /** 书名关键词（模糊匹配，可空；作者侧找书用，idx_author_id 前缀过滤后量小无性能顾虑） */
    private String keyword;
}
