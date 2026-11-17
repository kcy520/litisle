package com.litisle.litisleai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/*
 * 通俗说明：
 * 控制"每个功能每天最多花多少钱"，单位是元。
 * 文字机审和图片机审合在一起算一份预算，摘要、标签、问答、润色、结构分析、相似推荐各自独立。
 * 花到预算的 80% 只提醒、不停服务；到 100% 就停止这个功能的新请求
 * （机审例外：不停，改为全部转人工并告警）。
 * 数值填 0 或负数表示暂不限额，只记账。
 */
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

    /** 相似推荐桶日预算（SIMILAR，板块五 5.1；纯检索 token=0 成本恒 0，桶仅保持分账完整性；≤0=未设预算） */
    private BigDecimal similar = BigDecimal.ZERO;

    /** 索引期打标桶日预算（LABELING，板块八 1.3：ai_labels 维度标注管线，索引链路消费；≤0=未设预算） */
    private BigDecimal labeling = BigDecimal.ZERO;

    /** 预警线比例（当日合计 ≥ 预算×该值返回 WARN，只提醒不停用） */
    private double warnRatio = 0.8;
}
