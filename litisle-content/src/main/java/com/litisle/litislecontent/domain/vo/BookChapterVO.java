package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 书籍章节 VO（BookDetailVO.chapters 列表项；章节正文即文章）
 * <p>title 为章节对应文章的标题（落地时与 ArticleMapper 批量 in 查询聚合，防 N+1）</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class BookChapterVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 章节ID（book_chapter.id，进度上报用） */
    private Long chapterId;

    /** 章节正文的文章ID */
    private Long articleId;

    /** 章节序号 */
    private Integer chapterNo;

    /** 章节标题（聚合自 article.title） */
    private String title;
}