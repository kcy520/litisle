package com.litisle.litislesearch.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.litisle.litislesearch.constant.SearchBizConstant;
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
    @JsonProperty(SearchBizConstant.FIELD_TITLE)
    private String title;

    /** 标签名列表（有变更才写） */
    @JsonProperty(SearchBizConstant.FIELD_TAGS)
    private List<String> tags;

    /** 摘要（有变更才写） */
    @JsonProperty(SearchBizConstant.FIELD_SUMMARY)
    private String summary;

    /** 分区 code 字符串（有变更才写） */
    @JsonProperty(SearchBizConstant.FIELD_ZONE)
    private String zone;

    /** 分类名（有变更才写） */
    @JsonProperty(SearchBizConstant.FIELD_CATEGORY)
    private String category;

    /** 更新时间（同步推进，保证 NEWEST 排序准确） */
    @JsonProperty(SearchBizConstant.FIELD_UPDATE_TIME)
    private LocalDateTime updateTime;
}
