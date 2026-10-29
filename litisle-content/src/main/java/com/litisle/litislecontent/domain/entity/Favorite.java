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
 * favorite 实体（litisle_content 库收藏表，阶段16：一期 content 临时承载单层收藏）
 * <p>(user_id, target_type, target_id) 唯一索引保证每人每目标一条收藏记录；
 * social 收藏夹立项后迁移 favorite_item 或另定合并方案</p>
 */
@Data
@Accessors(chain = true)
@TableName("favorite")
public class Favorite implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 收藏用户ID
     * <p>bigint，联合唯一键 uk_user_target 之一</p>
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 收藏目标类型
     * <p>tinyint，1-文章 2-书籍（FavoriteTargetTypeEnum），联合唯一键 uk_user_target 之一</p>
     */
    @TableField("target_type")
    private Integer targetType;

    /**
     * 收藏目标ID
     * <p>bigint，多态指向：target_type=1 时为 article.id，target_type=2 时为 book.id；联合唯一键 uk_user_target 之一</p>
     */
    @TableField("target_id")
    private Long targetId;

    /**
     * 创建时间
     * <p>即收藏时间，列表按 create_time 倒序分页</p>
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}
