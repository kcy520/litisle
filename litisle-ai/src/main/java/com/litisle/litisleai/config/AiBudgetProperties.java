package com.litisle.litisleai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 预算分账配置（D15：机审桶 = 文本+图片合并，SUMMARY/TAGGING/QA 各自独立桶）
 *
 * <p>【口径】预算值 ≤0 表示未设预算，只计量不限额；预警线固定 80%（只提醒不停用）；
 * 100% 只停该用途新请求，机审桶到 100% 不停调用（转全量人工 + 告警）。</p>
 *
 * <p>【配置】前缀 litisle.ai.budget，数值为该桶每日成本上限（元）；运营可经 Nacos 调整。</p>
 */
@Data
@ConfigurationProperties(prefix = "litisle.ai.budget")
public class AiBudgetProperties {

    /** 机审桶日预算（MODERATION_TEXT + MODERATION_IMAGE 合并计费；≤0=未设预算） */
    private BigDecimal moderation = BigDecimal.ZERO;

    /** 摘要桶日预算（SUMMARY；≤0=未设预算） */
    private BigDecimal summary = BigDecimal.ZERO;

    /** 标签桶日预算（TAGGING；≤0=未设预算） */
    private BigDecimal tagging = BigDecimal.ZERO;

    /** 问答桶日预算（QA；≤0=未设预算） */
    private BigDecimal qa = BigDecimal.ZERO;

    /** 润色桶日预算（POLISH，板块三 3.3；≤0=未设预算） */
    private BigDecimal polish = BigDecimal.ZERO;

    /** 结构分析桶日预算（STRUCTURE，板块三 3.3；≤0=未设预算） */
    private BigDecimal structure = BigDecimal.ZERO;

    /** 预警线比例（当日合计 ≥ 预算×该值返回 WARN，只提醒不停用） */
    private double warnRatio = 0.8;
}
