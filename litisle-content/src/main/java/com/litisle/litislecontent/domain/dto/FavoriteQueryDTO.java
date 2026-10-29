package com.litisle.litislecontent.domain.dto;

import com.litisle.commoncore.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 我的收藏列表查询 DTO（阶段16）。
 * <p>分页参数（pageNo/pageSize/sortBy）由 PageQuery 统一承载；
 * Service 侧用 Page.of 裸分页（SQL 已显式排序，不走 PageQuery 默认 update_time 排序）</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FavoriteQueryDTO extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 收藏目标类型（可选）
     * <p>null=全部混合时间线；1=仅文章；2=仅书籍；非法值由 Service 抛 PARAM_ERROR</p>
     */
    private Integer targetType;
}
