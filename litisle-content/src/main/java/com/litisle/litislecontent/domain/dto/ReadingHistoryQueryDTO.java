package com.litisle.litislecontent.domain.dto;

import com.litisle.commoncore.query.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 我的阅读历史列表查询入参（GET /api/content/reading-history 专用）
 * <p>继承 PageQuery：pageNo/pageSize 已内置默认 1/10 与上限钳制（防恶意大页）；
 * bizType 三态：null=全部（书籍+文章混合时间线）/ 1=书籍 / 2=独立文章，
 * 非法值由 Service 层白名单校验拒绝（PARAM_ERROR）</p>
 *
 * @author litisle
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReadingHistoryQueryDTO extends PageQuery {

    /** 阅读对象类型过滤（可空=全部）：1-书籍 2-独立文章；取值与 ReadingTargetTypeEnum 的 code 一致，由 Service 统一 resolve 校验 */
    private Integer bizType;
}
