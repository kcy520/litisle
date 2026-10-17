package com.litisle.litislecontent.domain.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * book_chapter 实体（litisle_content 库书籍章节表，文章作为章节挂到书籍）
 * <p>(book_id, chapter_no) 唯一索引保证章节序号不重复，序号即排序键</p>
 */
@Data
@Accessors(chain = true)
@TableName("book_chapter")
public class BookChapter implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 章节ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 书籍ID
     * <p>bigint，联合唯一键 uk_book_chapter_no 之一</p>
     */
    @TableField("book_id")
    private Long bookId;

    /**
     * 文章ID
     * <p>bigint，章节正文即文章</p>
     */
    @TableField("article_id")
    private Long articleId;

    /**
     * 章节序号
     * <p>int，(book_id, chapter_no) 唯一，序号即排序键</p>
     */
    @TableField("chapter_no")
    private Integer chapterNo;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}