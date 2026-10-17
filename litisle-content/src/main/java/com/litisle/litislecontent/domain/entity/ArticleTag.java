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
 * article_tag 实体（litisle_content 库文章-标签关联表，多对多）
 * <p>(article_id, tag_id) 唯一索引 + 冲突幂等跳过；不参与推荐流排序权重，仅聚合列表页</p>
 */
@Data
@Accessors(chain = true)
@TableName("article_tag")
public class ArticleTag implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文章ID
     * <p>bigint，联合唯一键 uk_article_tag 之一；每篇文章上限 5 个标签</p>
     */
    @TableField("article_id")
    private Long articleId;

    /**
     * 标签ID
     * <p>bigint，联合唯一键 uk_article_tag 之一，索引 idx_tag_id 支撑按标签聚合文章</p>
     */
    @TableField("tag_id")
    private Long tagId;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}