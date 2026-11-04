package com.litisle.litisleapi.vo.search;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户索引命中 VO（user_idx 文档投影，跨服务检索契约）
 *
 * 【使用场景】SearchResultVO.userList 元素（type=user 时返回）
 *
 * 【设计要点】
 * - 字段对齐 Search-Architecture.md §四 user_idx 设计（笔名 text+ik 模糊匹配 + 简介）
 * - 只回传公开资料字段（笔名/简介），隐私字段不进契约
 *
 * @author litisle
 */
@Data
public class UserDocVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 笔名（user_idx 投影） */
    private String penName;

    /** 个人简介（user_idx 投影） */
    private String bio;

    /** 命中字段高亮片段（未命中高亮时为空） */
    private String highlight;
}
