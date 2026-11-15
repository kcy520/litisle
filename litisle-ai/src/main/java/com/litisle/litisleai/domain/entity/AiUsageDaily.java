package com.litisle.litisleai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ai_usage_daily AI 用量日汇总实体（定时任务按 stat_date+user_id+purpose 幂等覆盖写；
 * 预算判定与 F9 个人用量读此表）
 *
 * <p>user_id=0 表示系统/机审侧无发起用户的汇总桶（规避 MySQL 唯一键对 NULL 不去重）。</p>
 *
 * @author litisle
 */
@Data
@TableName("ai_usage_daily")
public class AiUsageDaily implements Serializable {

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 统计日期（按明细 occurred_at 归属；预算按自然日 00:00 重置） */
    @TableField("stat_date")
    private LocalDate statDate;

    /** 用户ID（0=系统/机审侧汇总桶） */
    @TableField("user_id")
    private Long userId;

    /** 用途编码（同 ai_token_usage.purpose） */
    @TableField("purpose")
    private String purpose;

    /** 调用次数（成功+失败） */
    @TableField("calls")
    private Integer calls;

    /** 输入 token 合计 */
    @TableField("tokens_in")
    private Long tokensIn;

    /** 输出 token 合计 */
    @TableField("tokens_out")
    private Long tokensOut;

    /** 成本合计（两位小数） */
    @TableField("cost")
    private BigDecimal cost;

    /** 失败调用次数 */
    @TableField("fail_calls")
    private Integer failCalls;

    /** 降级期调用次数 */
    @TableField("degraded_calls")
    private Integer degradedCalls;

    /** 创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 更新时间（DB on update 兜底） */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
