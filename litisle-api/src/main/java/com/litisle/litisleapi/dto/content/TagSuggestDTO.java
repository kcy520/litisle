package com.litisle.litisleapi.dto.content;

import lombok.Data;

import java.io.Serializable;

/**
 * 标签联想 DTO（search 联想接口聚合 content 标签池用，批次六 6.1）。
 *
 * 【设计口径】标签池唯一数据源（SSOT）在 content，search 不建标签索引、不写标签表，
 * 只做只读聚合，避免双写漂移。
 *
 * @author litisle
 */
@Data
public class TagSuggestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标签 ID（前端可选用于精确筛选） */
    private Long id;

    /** 标签名（联想展示文本） */
    private String name;
}
