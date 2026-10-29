package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 阅读历史 VO（书籍维度续读出参；无历史/章节或文章已删时 Service 返回 null，调用方按无续读处理）
 * <p>供续读接口与阶段17 书籍详情「上次读到第N章」展示</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ReadingHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书籍ID */
    private Long bookId;

    /** 最后阅读章节ID */
    private Long chapterId;

    /** 最后阅读章节序号（来自 book_chapter.chapter_no） */
    private Integer chapterNo;

    /** 最后阅读章节标题（来自章节文章 title；文章已删时整体返回 null） */
    private String chapterTitle;

    /** 最后阅读时间（reading_history.update_time） */
    private LocalDateTime lastReadTime;
}
