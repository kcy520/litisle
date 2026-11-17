package com.litisle.litisleai.vo;

import com.litisle.litisleai.dto.SimilarArticleCard;
import lombok.Builder;
import lombok.Data;

/**
 * 发布前查重响应（板块十二 4.2：risk=相似度超外置阈值；articles=命中文章卡片
 * （title/author/score，供前端展示重复风险来源）；explanation=「哪里相似」说明
 * （仅 explain=true 且命中时返回））
 *
 * @author litisle
 */
@Data
@Builder
public class DuplicateCheckResponse {

    /** 是否存在重复风险（命中文章最高相似度 ≥ 外置阈值 duplicate-score-threshold） */
    private Boolean risk;

    /** 本次判定使用的相似度阈值（外置配置回显，便于前端口径对齐） */
    private Double threshold;

    /** 「哪里相似」说明文本（仅 explain=true 且命中时生成；null=未生成） */
    private String explanation;

    /** 命中文章卡片列表（title/authorId/authorName/similarity；无命中为空列表） */
    private java.util.List<SimilarArticleCard> articles;
}
