package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.WordSceneEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * sensitive_word_version 实体（词库版本表，DFA 热更/缓存失效的版本单一来源）
 * <p>任何变更（单条/批量增删改、启停、黑白切换）在同一事务内 version+1；Redis Pub 携带此值，
 * 消费端 newVersion > curVersion 才重建（防乱序/重复 Pub 旧覆盖新）</p>
 * <p>MyBatis-Plus：@TableName("sensitive_word_version") + @TableId(value="scene", type=INPUT)</p>
 */
@Data
@Accessors(chain = true)
@TableName("sensitive_word_version")
public class SensitiveWordVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 场景：ARTICLE/COMMENT/USERNAME/MESSAGE（主键）
     */
    @TableId(value = "scene", type = IdType.INPUT)
    private WordSceneEnum scene;

    /**
     * 该 scene 词库当前版本（scene 级单调递增，缓存 key/留痕快照均取此单一来源）
     */
    @TableField("version")
    private Long version;

    /**
     * 最后变更人ID（审计）
     */
    @TableField("updated_by")
    private Long updatedBy;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}