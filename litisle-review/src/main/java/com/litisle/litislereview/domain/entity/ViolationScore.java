package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.LastPunishActionEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * violation_score 实体（违规分累积表，窄状态表）
 * <p>用 user_id 作主键：一用户一行，分数累加用 SQL 原子自增（set score = score + Δ）与状态列条件更新，
 * 不引入 version 乐观锁——重算依据在 punish_log 流水（每行 Δ 见 punish_log.score 快照）</p>
 * <p>重放口径两作用域分列：① 展示/预警=全部 status=1 生效行累计（含机审 action=3）；
 * ② 自动禁言/封禁阈值=只重放 action∈{1,2} 的 status=1 行</p>
 * <p>MyBatis-Plus：@TableName("violation_score") + @TableId(value="user_id", type=INPUT)</p>
 */
@Data
@Accessors(chain = true)
@TableName("violation_score")
public class ViolationScore implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID（主键，一用户一行）
     */
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /**
     * 违规分累计
     */
    @TableField("score")
    private Integer score;

    /**
     * 上次处罚动作：0-无 1-禁言 2-封禁（只记影响登录态的处罚）
     * <p>punish_log.action 的 3-内容下架/4-警告 仅落流水不改本表</p>
     */
    @TableField("last_punish_action")
    private LastPunishActionEnum lastPunishAction;

    /**
     * 禁言截止时间
     */
    @TableField("frozen_until")
    private LocalDateTime frozenUntil;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}