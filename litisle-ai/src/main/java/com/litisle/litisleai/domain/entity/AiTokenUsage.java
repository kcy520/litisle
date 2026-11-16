package com.litisle.litisleai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import com.litisle.litisleai.enums.AiUsageStatus;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ai_token_usage AI Token 用量明细实体（只追加，不更新；按用途打标的唯一事实来源）
 *
 * <p>表结构：docker/init/mysql/schema/ai-schema.sql（2026-11-15 增 call_seq，
 * 幂等键 = request_id + call_seq 联合唯一）。归属日期以 occurred_at 为权威源。</p>
 *
 * <p>MyBatis-Plus：@TableName + @TableId(AUTO)；创建时间由 DB 默认值兜底。</p>
 *
 * @author litisle
 */
@Data
@TableName("ai_token_usage")
public class AiTokenUsage implements Serializable {

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 请求幂等键（业务侧生成，重试复用同一键） */
    @TableField("request_id")
    private String requestId;

    /** 调用序号（同一业务请求的第 N 次模型调用；重试复用同一序号，与 request_id 联合唯一） */
    @TableField("call_seq")
    private Integer callSeq;

    /** 发起用户ID（机审无发起用户时为空） */
    @TableField("user_id")
    private Long userId;

    /** 用途打标（AiPurposeEnum.code，计费与预算只认前五类；@EnumValue 入库映射） */
    @TableField("purpose")
    private AiPurposeEnum purpose;

    /** 业务场景快照（可空，与 review_task.scene 同名） */
    @TableField("scene")
    private String scene;

    /** 判定口径版本快照（文本 = 配置的模型标识） */
    @TableField("model_version")
    private String modelVersion;

    /** 提示词版本（文本与 model_version 同值；图片无提示词留空） */
    @TableField("prompt_version")
    private String promptVersion;

    /** 输入 token 数（失败调用记 0） */
    @TableField("token_in")
    private Integer tokenIn;

    /** 输出 token 数（失败调用记 0） */
    @TableField("token_out")
    private Integer tokenOut;

    /** 本次调用成本（= token 数 × ai_pricing 单价，两位小数） */
    @TableField("cost")
    private BigDecimal cost;

    /** 调用结果：1-SUCCESS 2-FAIL（失败也留明细供对账） */
    @TableField("status")
    private AiUsageStatus status;

    /** 降级标记：0-正常 1-降级期产生（不计入效果评估样本） */
    @TableField("degraded")
    private Integer degraded;

    /** 调用完成时间（归属日期权威源，不按发起时间） */
    @TableField("occurred_at")
    private LocalDateTime occurredAt;

    /** 写入时间（DB 默认值兜底） */
    @TableField("create_time")
    private LocalDateTime createTime;
}
