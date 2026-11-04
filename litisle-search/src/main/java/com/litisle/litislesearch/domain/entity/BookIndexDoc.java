package com.litisle.litislesearch.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.litisle.litislesearch.constant.SearchBizConstant;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 书籍索引文档（book_idx 的 ES 文档映射实体，非 MySQL 实体，批次四 4.1 读侧投影）。
 *
 * 【说明】字段与 EsIndexInitializer#bookMapping 一一对应，同时承担读侧反序列化与
 * 批次五 B4 事件同步写入（键 = bookId）。
 * 【字段口径】批次五已补齐 authorId 映射，BookDocVO.authorId 由本字段解析回填。
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

    /** 作者 ID（keyword，批次五补映射；供 BookDocVO.authorId 回填与作者维度筛选） */
    @JsonProperty(SearchBizConstant.FIELD_AUTHOR_ID)
    private String authorId;

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
