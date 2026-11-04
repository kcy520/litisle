package com.litisle.litislesearch.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.litisle.litislesearch.constant.SearchBizConstant;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户索引文档（user_idx 的 ES 文档映射实体，非 MySQL 实体，批次四 4.1 读侧投影）。
 *
 * 【说明】字段与 EsIndexInitializer#userMapping 一一对应，同时承担读侧反序列化与
 * 批次五 B5 事件同步写入（键 = userId，写入见 UserIndexServiceImpl）。
 * 【隐私】只承载公开资料字段（笔名/简介），不定义任何隐私字段。
 *
 * @author litisle
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserIndexDoc {

    /** 文档主键：userId 字符串（mapping keyword） */
    @JsonProperty(SearchBizConstant.FIELD_ID)
    private String id;

    /** 笔名（multi_match 检索字段） */
    @JsonProperty(SearchBizConstant.FIELD_PEN_NAME)
    private String penName;

    /** 个人简介（multi_match 检索字段） */
    @JsonProperty(SearchBizConstant.FIELD_BIO)
    private String bio;

    /** 更新时间（NEWEST 排序） */
    @JsonProperty(SearchBizConstant.FIELD_UPDATE_TIME)
    private LocalDateTime updateTime;
}
