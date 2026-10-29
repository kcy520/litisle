package com.litisle.litislecontent.domain.dto;

import com.litisle.commoncore.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 章节列表分页查询参数（阶段17：GET /book/{id}/chapters）
 * <p>仅继承 PageQuery 分页字段（pageNo/pageSize），无业务过滤条件；
 * 构造器设默认 pageSize=50（章节选择页一次尽量列全），仍受 PageQuery 上限钳制（MAX_PAGE_SIZE=100）</p>
 *
 * @author litisle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BookChapterQueryDTO extends PageQuery {

    {
        // 章节列表业务默认每页 50 条（章节选择页）；覆写父类默认值，不重复声明同名字段
        setPageSize(50);
    }
}
