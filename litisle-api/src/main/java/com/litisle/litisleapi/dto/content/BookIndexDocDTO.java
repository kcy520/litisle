package com.litisle.litisleapi.dto.content;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 书籍索引回查 DTO（search 建 book_idx 索引用，content 服务提供）。
 *
 * 【用途】search 消费 content.book-changed 事件后按 bookId 回查，
 * 事件体只带定位键，书名/作者/简介/分类等建索引字段一律以本 DTO 为准。
 *
 * 【字段口径】
 * - status 返回 BookStatusEnum 的 code：0-连载中 1-已完结 2-下架（下架不入索引）
 * - delFlag 返回 DelFlag 的 code：0-未删除 1-已删除（已删除不入索引）
 * - authorName 为 best-effort：用户服务不可用时为 null，不阻断建索引
 *
 * @author litisle
 */
@Data
public class BookIndexDocDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书籍 ID（索引文档主键） */
    private Long bookId;

    /** 作者 ID（索引作者维度筛选/结果回填，批次五补齐的 book_idx 字段） */
    private Long authorId;

    /** 作者笔名/昵称（可为空：仅展示与检索用） */
    private String authorName;

    /** 书名（multi_match 最高权重字段） */
    private String title;

    /** 书籍简介（书籍 intro，可为空） */
    private String summary;

    /** 分类（keyword 筛选维度） */
    private String category;

    /** 书籍状态枚举 code（0-连载中 1-已完结 2-下架） */
    private Integer status;

    /** 删除标志枚举 code（0-未删除 1-已删除） */
    private Integer delFlag;

    /** 更新时间（NEWEST 排序） */
    private LocalDateTime updateTime;
}
