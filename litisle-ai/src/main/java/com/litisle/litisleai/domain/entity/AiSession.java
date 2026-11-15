package com.litisle.litisleai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ai_session AI 会话实体（板块三 3.1：小悦有状态会话；暂存区 LRU 淘汰 + 已保存区）
 *
 * <p>表结构：docker/init/mysql/schema/ai-schema.sql（2026-11-15 随板块三 3.1 建表，
 * DDL 以 Sql-Document §五为准）。治理口径 D25：暂存区 200 会话上限，超限按
 * last_active_time LRU 淘汰；生成内容留存 90 天。</p>
 *
 * @author litisle
 */
@Data
@TableName("ai_session")
public class AiSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID（会话ID，@MemoryId 承载） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID（会话归属，删除与列表的归属校验依据） */
    @TableField("user_id")
    private Long userId;

    /** Agent 类型：XIAOYUE / XIAOSHEN */
    @TableField("agent_type")
    private String agentType;

    /** 会话标题（首条消息摘要） */
    @TableField("title")
    private String title;

    /** 小悦模式：POLISH/STRUCTURE/SUMMARY/DEEP */
    @TableField("mode")
    private String mode;

    /** 是否暂存区：1-是 0-已保存区 */
    @TableField("is_lru")
    private Integer isLru;

    /** 最后活跃时间（LRU 淘汰依据，每次对话 touch 刷新） */
    @TableField("last_active_time")
    private LocalDateTime lastActiveTime;

    /** 删除标志（软删=懒删；物理清理由定时兜底任务执行） */
    @TableField("del_flag")
    private Integer delFlag;

    /** 创建时间（DB 默认值兜底） */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 更新时间（DB 默认值兜底） */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
