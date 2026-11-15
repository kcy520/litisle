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
 * ai_pricing 模型单价配置实体（成本口径唯一来源；配置变更只影响未来日期，不回改历史汇总）
 *
 * <p>同版本调价插入新行（effective_from 生效日），不 UPDATE 旧行，保历史成本可复算。</p>
 *
 * @author litisle
 */
@Data
@TableName("ai_pricing")
public class AiPricing implements Serializable {

    /** 主键ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 模型/口径版本（对应 ai_token_usage.model_version） */
    @TableField("model_version")
    private String modelVersion;

    /** 币种（默认 CNY） */
    @TableField("currency")
    private String currency;

    /** 输入单价（每 token，8 位小数） */
    @TableField("unit_price_in")
    private BigDecimal unitPriceIn;

    /** 输出单价（每 token，8 位小数） */
    @TableField("unit_price_out")
    private BigDecimal unitPriceOut;

    /** 生效日期（同版本调价插入新行） */
    @TableField("effective_from")
    private LocalDate effectiveFrom;

    /** 创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
