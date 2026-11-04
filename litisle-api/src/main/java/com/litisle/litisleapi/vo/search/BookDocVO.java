package com.litisle.litisleapi.vo.search;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 书籍索引命中 VO（book_idx 文档投影，跨服务检索契约）
 *
 * 【使用场景】SearchResultVO.bookList 元素（type=book 时返回）
 *
 * 【设计要点】字段对齐 Search-Architecture.md §四 book_idx 设计（书名/作者/简介 text+ik + 分类 keyword）
 *
 * @author litisle
 */
@Data
public class BookDocVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书籍 ID */
    private Long bookId;

    /** 书名 */
    private String title;

    /** 书籍简介 */
    private String summary;

    /** 分类（book_idx category keyword） */
    private String category;

    /** 作者用户 ID */
    private Long authorId;

    /** 作者笔名 */
    private String authorName;

    /** 最后更新时间（索引 updateTime 投影） */
    private LocalDateTime updateTime;

    /** 命中字段高亮片段（未命中高亮时为空） */
    private String highlight;
}
