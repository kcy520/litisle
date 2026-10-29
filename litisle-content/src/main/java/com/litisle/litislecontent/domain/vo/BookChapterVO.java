package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 书籍章节 VO（阶段17：章节列表接口 GET /book/{id}/chapters 出参项；章节正文即文章）
 * <p>title 为章节对应文章的标题（SQL join article 投影）；
 * 「上次读到」标记不由列表逐项回填——前端用书籍详情的 lastReadChapterId 与本列表 chapterId 对比高亮，
 * 避免额外字段与查询</p>
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