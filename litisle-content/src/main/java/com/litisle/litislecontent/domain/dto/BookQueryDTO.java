package com.litisle.litislecontent.domain.dto;

import com.litisle.commoncore.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公开书籍列表查询条件（GET /book/list 专用，P2 书籍发现入口 2026-10-28）
 * <p>继承 PageQuery：pageNo/pageSize 内置规范化兜底；排序默认 update_time 倒序
 * （匿名端点 sortBy 仅通用时间列白名单内生效，白名单外静默回退默认排序）</p>
 * <p>口径约束：status != 下架 && del_flag=0 为 Service 硬条件（不占参数位、不可伪造）；
 * 一期不做 keyword 搜索（search 模块书籍索引为外部依赖，无索引 SQL LIKE 全表扫不立项）</p>
 *
 * @author litisle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BookQueryDTO extends PageQuery {

    /** 分类筛选（可空=全部；book.category 为自由字符串（varchar(20)，默认"小说"），精确匹配） */
    private String category;
}
