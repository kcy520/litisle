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
 * user_like 点赞实体（批次十一 11.8，litisle_content 库）。
 *
 * <p>一期 content 临时承载点赞功能（social 模块未落地）；取消点赞按已拍板口径物理删除本行，
 * 与 favorite 表行为一致。唯一索引 (user_id, target_type, target_id) 是「重复点赞幂等」的依据。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
@TableName("user_like")
public class UserLike implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID（MyBatis-Plus 自增主键） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 点赞用户ID（唯一键 uk_user_target 之一） */
    @TableField("user_id")
    private Long userId;

    /** 目标类型（1-文章 2-书籍，口径与 FavoriteTargetTypeEnum 一致；唯一键之一） */
    @TableField("target_type")
    private Integer targetType;

    /** 目标ID（target_type=1 时为 article.id，=2 时为 book.id；唯一键之一） */
    @TableField("target_id")
    private Long targetId;

    /** 点赞时间（取消点赞即物理删除本行，不保留取消记录） */
    @TableField("create_time")
    private LocalDateTime createTime;
}
