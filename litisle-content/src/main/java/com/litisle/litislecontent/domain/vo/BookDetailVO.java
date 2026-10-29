package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.BookStatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 书籍详情 VO（阶段17：不含全量目录，章节列表改走分页接口 GET /book/{id}/chapters；
 * 首章/最新章/上次阅读信息随详情一次返回，支撑「继续阅读」与章节页定位）
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

    /**
     * 可见章节总数
     * <p>按查看者可见性过滤后的章节数（作者看到全部，非作者只看到已发布公开/关注者可见的）</p>
     */
    private Long chapterCount;

    /** 第一章章节ID（无可见章节为 null） */
    private Long firstChapterId;

    /** 第一章序号 */
    private Integer firstChapterNo;

    /** 最新一章章节ID */
    private Long latestChapterId;

    /** 最新一章序号 */
    private Integer latestChapterNo;

    /**
     * 上次阅读章节ID
     * <p>来自阅读历史（uk_user_biz 书籍行），匿名或无历史为 null——前端据此展示/隐藏「继续阅读」</p>
     */
    private Long lastReadChapterId;

    /** 上次阅读章节序号 */
    private Integer lastReadChapterNo;

    /** 上次阅读章节标题 */
    private String lastReadChapterTitle;

    /** 上次阅读时间 */
    private LocalDateTime lastReadTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /**
     * 当前查看者是否已收藏该书
     * <p>true-已收藏 false-未收藏；匿名查看恒为 false；
     * 注意与章节详情内嵌 article.favorited（文章收藏）区分，前端不要混用</p>
     */
    private Boolean favorited;
}