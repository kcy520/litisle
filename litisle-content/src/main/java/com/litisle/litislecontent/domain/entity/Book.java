package com.litisle.litislecontent.domain.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.litislecontent.enums.BookStatusEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * book 实体（litisle_content 库书籍/合集表）
 * <p>文章作为章节挂到书籍（见 book_chapter），默认状态连载中</p>
 */
@Data
@Accessors(chain = true)
@TableName("book")
public class Book implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 书籍ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 作者ID
     * <p>bigint，索引 idx_author_id</p>
     */
    @TableField("author_id")
    private Long authorId;

    /**
     * 书名
     * <p>varchar(100)</p>
     */
    @Size(max = 100, message = "书名长度不能超过 100 个字符")
    @TableField("title")
    private String title;

    /**
     * 简介
     * <p>varchar(500)，可空</p>
     */
    @Size(max = 500, message = "简介长度不能超过 500 个字符")
    @TableField("intro")
    private String intro;

    /**
     * 封面文件ID（SeaweedFS）
     * <p>varchar(64)，可空</p>
     */
    @Size(max = 64, message = "封面文件ID长度不能超过 64 个字符")
    @TableField("cover_file_id")
    private String coverFileId;

    /**
     * 分类（单选）
     * <p>varchar(20)，默认"小说"</p>
     */
    @Size(max = 20, message = "分类长度不能超过 20 个字符")
    @TableField("category")
    private String category;

    /**
     * 状态
     * <p>SERIALING(0)-连载中（默认）/ COMPLETED(1)-已完结 / TAKEN_DOWN(2)-下架</p>
     */
    @TableField("status")
    private BookStatusEnum status;

    /**
     * 删除标志
     * <p>NOT_DELETED(0)-未删除 / DELETED(1)-已删除</p>
     */
    @TableField("del_flag")
    private DelFlag delFlag;

    /**
     * 乐观锁版本号
     */
    @TableField("version")
    private Integer version;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}