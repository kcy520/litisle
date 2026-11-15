package com.litisle.litisleai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ai_message AI 消息实体（板块三 3.1：会话消息全量存档；跟随会话级联清理，不加 del_flag）
 *
 * <p>表结构：docker/init/mysql/schema/ai-schema.sql（2026-11-15 随板块三 3.1 建表）。
 * prompt/completion token 逐条记录（记于该轮终答行）与 ai_token_usage 日汇总对账，
 * 防统计口径漂移。</p>
 *
 * @author litisle
 */
@Data
@TableName("ai_message")
public class AiMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 会话ID（归属会话，级联清理依据） */
    @TableField("session_id")
    private Long sessionId;

    /** 角色：USER / ASSISTANT / SYSTEM */
    @TableField("role")
    private String role;

    /** 消息内容 */
    @TableField("content")
    private String content;

    /** 本条消息 token 数（当前未接估算器，默认 0 预留） */
    @TableField("token_cnt")
    private Integer tokenCnt;

    /** 本条消息输入 token 消耗（记于该轮终答行） */
    @TableField("prompt_tokens")
    private Integer promptTokens;

    /** 本条消息输出 token 消耗（记于该轮终答行） */
    @TableField("completion_tokens")
    private Integer completionTokens;

    /** 创建时间（DB 默认值兜底） */
    @TableField("create_time")
    private LocalDateTime createTime;
}
