package com.litisle.litisleapi.dto.ai;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 索引期打标响应 DTO（板块八 1.3：ai_labels 维度标签集跨服务契约）
 *
 * <p>【口径】labels 为「维度名 → 标签值」映射，key 与维度注册表（litisle.search.ai-labels.dimensions）
 * 维度名一致；受控词表维度取值必在词表内，语义维度为自由短语。低置信维度不输出（缺失即未标注），
 * ai 侧不硬造标签；search 侧按缺失维度直接留空（该维度过滤退化为语义兜底）。</p>
 *
 * @author litisle
 */
@Data
public class AiLabelResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 维度标签集（key=维度名，如 style/depth/structure/topic；无置信标签时为空映射） */
    private Map<String, String> labels;
}
