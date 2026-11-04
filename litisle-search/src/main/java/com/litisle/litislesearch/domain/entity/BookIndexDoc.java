package com.litisle.litislesearch.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.litisle.litislesearch.constant.SearchBizConstant;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 书籍索引文档（book_idx 的 ES 文档映射实体，非 MySQL 实体，批次四 4.1 读侧投影）。
 *
 * 【说明】字段与 EsIndexInitializer#bookMapping 一一对应，仅用于检索结果反序列化；
 * 书籍索引写入链路（同步事件）批次五落地，本类不承担写入职责。
 * 【字段口径】book_idx 当前 mapping 未包含 authorId，故不在此定义该字段（VO 侧 authorId 暂为 null）。
 *
 * @author litisle
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookIndexDoc {

    /** 文档主键：bookId 字符串（mapping keyword） */
    @JsonProperty(SearchBizConstant.FIELD_ID)
    private String id;

    /** 书名（multi_match 检索字段） */
    @JsonProperty(SearchBizConstant.FIELD_TITLE)
    private String title;

    /** 作者名（multi_match 检索字段） */
    @JsonProperty(SearchBizConstant.FIELD_AUTHOR_NAME)
    private String authorName;

    /** 书籍简介（multi_match 检索字段） */
    @JsonProperty(SearchBizConstant.FIELD_SUMMARY)
    private String summary;

    /** 分类（keyword 筛选维度） */
    @JsonProperty(SearchBizConstant.FIELD_CATEGORY)
    private String category;

    /** 更新时间（NEWEST 排序） */
    @JsonProperty(SearchBizConstant.FIELD_UPDATE_TIME)
    private LocalDateTime updateTime;
}
