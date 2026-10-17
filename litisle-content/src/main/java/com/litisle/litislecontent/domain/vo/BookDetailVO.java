package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.BookStatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 书籍详情 VO（含章节目录，按 chapterNo 升序——序号即排序键）
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class BookDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书籍ID */
    private Long id;

    /** 作者用户ID */
    private Long authorId;

    /** 书名 */
    private String title;

    /** 简介 */
    private String intro;

    /** 封面文件ID */
    private String coverFileId;

    /** 分类 */
    private String category;

    /** 状态（连载中/已完结/下架） */
    private BookStatusEnum status;

    /** 章节目录（chapterNo 升序） */
    private List<BookChapterVO> chapters;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}