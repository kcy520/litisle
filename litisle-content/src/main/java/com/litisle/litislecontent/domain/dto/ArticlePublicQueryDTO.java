package com.litisle.litislecontent.domain.dto;

import com.litisle.commoncore.query.PageQuery;
import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 匿名公开列表查询条件（GET /article/list 专用）
 * <p>继承 PageQuery：pageNo/pageSize/sortBy/isAsc 已内置规范化（防恶意大页）</p>
 * <p>口径约束：本 DTO 是公开端点的全部可传参数——status/visibility 由业务层硬编码
 * （仅"已发布+公开"匿名可见），类型上不存在状态字段，前端传什么都无法影响过滤条件；
 * 排序固定 update_time 降序（Service 显式 OrderItem，不开放匿名 sortBy）</p>
 *
 * @author litisle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ArticlePublicQueryDTO extends PageQuery {

    /** 分区筛选（可空=全部；枚举已实现 IntDictEnum，name/code/desc 三值兼容绑定） */
    private ZoneEnum zone;

    /** 分类筛选（可空=全部；枚举反序列化即白名单校验，脏值 400 而非静默空结果） */
    private ArticleCategoryEnum category;
}
