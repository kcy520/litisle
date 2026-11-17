package com.litisle.litislesearch.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.litisle.litislesearch.constant.SearchFieldConstant;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章元数据局部更新文档（article_idx partial update 专用）。
 *
 * 【用途】content.article-updated 事件消费时只更新标题/标签/摘要等元数据字段，
 * 不动正文、向量与 article_chunks 切片（架构文档 §四 / 批次A A2 定案）。
 * 【关键点】@JsonInclude(NON_NULL) 必须保留：ES update+doc 语义是「给出什么就覆盖什么」，
 * 若把 null 也序列化进去，会把未变更字段清空。
 *
 * @author litisle
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArticleMetaDoc {

    /** 标题（有变更才写） */
    @JsonProperty(SearchFieldConstant.FIELD_TITLE)
    private String title;

    /** 标签名列表（有变更才写） */
    @JsonProperty(SearchFieldConstant.FIELD_TAGS)
    private List<String> tags;

    /** 摘要（有变更才写） */
    @JsonProperty(SearchFieldConstant.FIELD_SUMMARY)
    private String summary;

    /** 分区 code 字符串（有变更才写） */
    @JsonProperty(SearchFieldConstant.FIELD_ZONE)
    private String zone;

    /** 分类名（有变更才写） */
    @JsonProperty(SearchFieldConstant.FIELD_CATEGORY)
    private String category;

    /** ai_labels 维度标签槽（板块八/search 板块二 2.2：改稿重新打标整体覆盖；打标失败不写保持原值） */
    @JsonProperty(SearchFieldConstant.FIELD_AI_LABELS)
    private java.util.Map<String, String> aiLabels;

    /** 正文字数（改稿按最新正文重算覆盖） */
    @JsonProperty(SearchFieldConstant.FIELD_WORD_COUNT)
    private Long wordCount;

    /** 更新时间（同步推进，保证 NEWEST 排序准确） */
    @JsonProperty(SearchFieldConstant.FIELD_UPDATE_TIME)
    private LocalDateTime updateTime;

    /** 文章向量（search 板块一 1.3 改稿联动：池化口径重算刷新；embed 失败为 null 不序列化即保持原值） */
    @JsonProperty(SearchFieldConstant.FIELD_VEC_DENSE)
    private List<Float> vecDense;
}
