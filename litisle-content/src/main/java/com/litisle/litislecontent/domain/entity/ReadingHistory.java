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
 * reading_history 实体（litisle_content 库阅读历史表，原 reading_progress 语义升级）
 * <p>单表承载书籍与独立文章两类阅读历史，(user_id, biz_type, biz_id) 唯一索引保证每人每类型每对象一条历史，update_time 即最后阅读时间</p>
 */
@Data
@Accessors(chain = true)
@TableName("reading_history")
public class ReadingHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 读者ID
     * <p>bigint，联合唯一键 uk_user_biz 之一</p>
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 阅读对象类型
     * <p>tinyint，1-书籍 2-独立文章，联合唯一键 uk_user_biz 之一</p>
     */
    @TableField("biz_type")
    private Integer bizType;

    /**
     * 阅读对象ID
     * <p>bigint，多态指向：biz_type=1 时为 book.id，biz_type=2 时为 article.id；联合唯一键 uk_user_biz 之一</p>
     */
    @TableField("biz_id")
    private Long bizId;

    /**
     * 最后阅读章节ID
     * <p>bigint，仅 biz_type=1（书籍）有值，指向 book_chapter.id，即上次阅读记录；独立文章无章节，为 null</p>
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