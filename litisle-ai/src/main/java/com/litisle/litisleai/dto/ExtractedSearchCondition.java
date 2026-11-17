package com.litisle.litisleai.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 条件提取结果载体（板块八 1.2：1 次 LLM 结构化提取的产出，ai 内部 DTO 不出服务边界）
 *
 * <p>【口径】filter 词表型维度统一收进 aiLabels 映射（key=注册表维度名）；语义维度（topic 等）
 * 已并入 queryText；authorName 为原始笔名，作者名 → ID 解析由编排层经 SearchClient 查 user_idx
 * 完成（普通索引查询非 LLM）。全字段空 = 无有效条件，调用方降级 L0。</p>
 *
 * @author litisle
 */
@Data
public class ExtractedSearchCondition implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 检索主旨语义文本（含风格与否定语义表达，semantic 维度已并入） */
    private String queryText;

    /** 指定作者笔名（原始值，待解析为 ID，可空） */
    private String authorName;

    /** 需排除的作者笔名（原始值，待解析为 ID，可空） */
    private String excludeAuthorName;

    /** 分类名（可空） */
    private String category;

    /** 标签列表（可空） */
    private List<String> tags;

    /** 词表型维度条件（key=注册表维度名如 style/depth/structure，value=词表值） */
    private Map<String, String> aiLabels = new LinkedHashMap<>();

    /** 正文字数下限（可空） */
    private Long wordCountMin;

    /** 正文字数上限（可空） */
    private Long wordCountMax;

    /** 发布时间起（yyyy-MM-dd，可空） */
    private String timeStart;

    /** 发布时间止（yyyy-MM-dd，可空） */
    private String timeEnd;

    /** 返回条数上限（可空） */
    private Integer topK;

    /** 排序意图（related/newest/heat/similarity-first，可空默认 related） */
    private String sortHint;

    /**
     * 判断是否存在任一有效条件（全空 = 无有效条件，调用方降级 L0 直通）
     *
     * @return true=至少一项条件有效
     */
    public boolean hasAnyCondition() {
        boolean hasText = queryText != null && !queryText.isBlank();
        boolean hasAuthor = (authorName != null && !authorName.isBlank())
                || (excludeAuthorName != null && !excludeAuthorName.isBlank());
        boolean hasFilter = (category != null && !category.isBlank())
                || (tags != null && !tags.isEmpty())
                || (aiLabels != null && !aiLabels.isEmpty());
        boolean hasRange = wordCountMin != null || wordCountMax != null
                || (timeStart != null && !timeStart.isBlank()) || (timeEnd != null && !timeEnd.isBlank());
        boolean hasParam = topK != null || (sortHint != null && !sortHint.isBlank());
        return hasText || hasAuthor || hasFilter || hasRange || hasParam;
    }
}
