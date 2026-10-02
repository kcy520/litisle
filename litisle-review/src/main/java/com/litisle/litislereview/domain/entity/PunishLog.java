package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.PunishActionEnum;
import com.litisle.litislereview.enums.PunishStatusEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * punish_log 实体（处罚流水表，append-only 行不删）
 * <p>申诉裁决=2-撤销 时 status 就地置 2 + violation_score 重算——允许状态列就地更新、行不删
 * （与 moderation_record 的真 append-only（改判追加新行）区分）</p>
 * <p>MyBatis-Plus：@TableName("punish_log") + @TableId(value="id", type=AUTO)</p>
 */
@Data
@Accessors(chain = true)
@TableName("punish_log")
public class PunishLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 处罚ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 被处罚用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 处罚动作：1-禁言 2-封禁 3-内容下架（含机审首发驳回）4-警告
     */
    @TableField("action")
    private PunishActionEnum action;

    /**
     * 本次处罚记入的违规分值快照（分值规则外置；落行快照使「按 status=1 行重放」确定可算）
     * <p>机审 action=3 通常记 0，不参与登录态阈值判定</p>
     */
    @TableField("score")
    private Integer score;

    /**
     * 处罚原因（UGC，回显须 HTML sanitize + 输出编码）
     */
    @Size(max = 200, message = "处罚原因长度不能超过 200 个字符")
    @TableField("reason")
    private String reason;

    /**
     * 关联违规业务类型（同 review_task.biz_type）
     */
    @TableField("biz_type")
    private ModerationBizTypeEnum bizType;

    /**
     * 关联违规业务ID
     */
    @TableField("biz_id")
    private Long bizId;

    /**
     * 关联审核任务ID（回溯审核链路）
     */
    @TableField("review_task_id")
    private Long reviewTaskId;

    /**
     * 处罚截止时间（永久处罚为 null）
     */
    @TableField("expire_time")
    private LocalDateTime expireTime;

    /**
     * 操作人ID（0-系统）
     */
    @TableField("operator_id")
    private Long operatorId;

    /**
     * 操作人名称（审计冗余）
     */
    @Size(max = 32, message = "操作人名称长度不能超过 32 个字符")
    @TableField("operator_name")
    private String operatorName;

    /**
     * 状态：1-生效 2-已撤销（申诉成立）
     */
    @TableField("status")
    private PunishStatusEnum status;

    /**
     * 撤销时间
     */
    @TableField("revoke_time")
    private LocalDateTime revokeTime;

    /**
     * 处罚时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}