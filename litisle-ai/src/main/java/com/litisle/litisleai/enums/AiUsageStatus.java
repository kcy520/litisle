package com.litisle.litisleai.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 调用结果状态（计量明细 status 字段取值）
 *
 * <p>失败调用也落一条明细（避免「失败即无成本」），供对账与 V5 熔断计数核对。</p>
 */
@Getter
@AllArgsConstructor
public enum AiUsageStatus {

    /** 调用成功 */
    SUCCESS(1),

    /** 调用失败（也留明细，token 按实际产生计，供应商未返回时为 0） */
    FAIL(2),
    ;

    /** 数据库存储值（@EnumValue：MybatisEnumTypeHandler 按此值与 DB 互转） */
    @com.baomidou.mybatisplus.annotation.EnumValue
    private final int code;
}
