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
 * reading_progress 实体（litisle_content 库阅读进度表）
 * <p>(user_id, book_id) 唯一索引保证每人每书一条进度，update_time 即最后阅读时间</p>
 */
@Data
@Accessors(chain = true)
@TableName("reading_progress")
public class ReadingProgress implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 读者ID
     * <p>bigint，联合唯一键 uk_user_book 之一</p>
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 书籍ID
     * <p>bigint，联合唯一键 uk_user_book 之一</p>
     */
    @TableField("book_id")
    private Long bookId;

    /**
     * 最后阅读章节ID
     * <p>bigint，指向 book_chapter.id</p>
     */
    @TableField("chapter_id")
    private Long chapterId;

    /**
     * 更新时间
     * <p>on update current_timestamp，即最后阅读时间</p>
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}