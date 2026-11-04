package com.litisle.litisleapi.vo.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 搜索联想结果项（批次六 6.1）。
 *
 * 【结构】type 区分联想来源，text 为展示/可直接用于检索的文本：
 * - KEYWORD：article_idx 书名 completion 联想（文章标题）
 * - AUTHOR：article_idx 作者名 completion 联想
 * - TAG：content 标签池前缀联想（不建标签索引，标签 SSOT 在 content）
 *
 * @author litisle
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SuggestVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 联想类型（KEYWORD / AUTHOR / TAG，取值见 SearchBizConstant.SUGGEST_TYPE_*） */
    private String type;

    /** 联想文本（前端展示，点击后可直接作为检索词） */
    private String text;
}
