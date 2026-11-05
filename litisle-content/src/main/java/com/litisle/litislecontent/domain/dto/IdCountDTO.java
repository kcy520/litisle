package com.litisle.litislecontent.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 按对象 ID 聚合计数结果（批次十 10.4：收藏数/阅读人数/章节数三个 group by 查询的返回行）
 *
 * <p>字段名与 SQL 别名一致（id/cnt），避免再引入 resultMap；只读不写库。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class IdCountDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 聚合对象 ID（book.id） */
    private Long id;

    /** 聚合计数（收藏数/阅读人数/章节数） */
    private Long cnt;
}
