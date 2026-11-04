package com.litisle.litislesearch.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.litisle.litislesearch.constant.SearchBizConstant;
import lombok.Data;

/**
 * 文章热度刷写文档（article_idx 部分更新载体，批次三 3.2）。
 *
 * 【用途】IndexHeatFlushJob 用 update + doc 局部更新热度相关字段：
 * 只写互动计数基数与 heat，绝不整篇 index（保住 vec_dense / content 不重写，防写放大）。
 * 【字段口径】计数为刷写后的绝对值；heat =（权重×计数之和）× 时间衰减因子（A5 公式）。
 *
 * @author litisle
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleHeatDoc {

    /** 刷写后的点赞总数绝对值（原值 + 窗口内增量） */
    @JsonProperty(SearchBizConstant.FIELD_LIKE_CNT)
    private Long likeCnt;

    /** 刷写后的评论总数绝对值（原值 + 窗口内增量） */
    @JsonProperty(SearchBizConstant.FIELD_COMMENT_CNT)
    private Long commentCnt;

    /** 刷写后的浏览量绝对值（content 侧落库值优先，未收到则沿用原值） */
    @JsonProperty(SearchBizConstant.FIELD_VIEW_CNT)
    private Long viewCnt;

    /** 重算后的互动热度（HEAT 排序与 function_score 加权因子使用） */
    @JsonProperty(SearchBizConstant.FIELD_HEAT)
    private Double heat;
}
