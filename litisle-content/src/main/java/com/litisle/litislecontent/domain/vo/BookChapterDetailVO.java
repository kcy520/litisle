package com.litisle.litislecontent.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 章节详情 VO（阶段17：GET /book/{id}/chapter/{chapterId} 出参）
 * <p>书籍上下文 + 章节信息 + 章节正文（复用 ArticleDetailVO，含正文渲染/配图/作者/计数）
 * + 相邻章节导航（prev/next 随详情一次返回，前端上一章/下一章直接用，不再查目录）</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class BookChapterDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书籍ID */
    private Long bookId;

    /** 书名 */
    private String bookTitle;

    /** 书籍作者用户ID */
    private Long bookAuthorId;

    /** 章节ID（book_chapter.id，阅读历史上报用） */
    private Long chapterId;

    /** 章节序号 */
    private Integer chapterNo;

    /** 章节标题 */
    private String chapterTitle;

    /** 可见章节总数（同 BookDetailVO.chapterCount 口径，章节页展示「第N/M章」） */
    private Long chapterCount;

    /**
     * 章节正文（复用文章详情）
     * <p>注意：内嵌 favorited 是「文章收藏」状态，不代表「书籍收藏」，
     * 书籍收藏看 BookDetailVO.favorited，前端不要混用</p>
     */
    private ArticleDetailVO article;

    /** 上一章章节ID（已是第一章或上一章不可见为 null，前端隐藏上一章按钮） */
    private Long prevChapterId;

    /** 上一章序号 */
    private Integer prevChapterNo;

    /** 下一章章节ID（已是最新章或下一章不可见为 null，前端隐藏下一章按钮） */
    private Long nextChapterId;

    /** 下一章序号 */
    private Integer nextChapterNo;
}
