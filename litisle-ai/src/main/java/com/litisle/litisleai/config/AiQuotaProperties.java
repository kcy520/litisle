package com.litisle.litisleai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户级配额配置（D5/V8：按 用户×用途×日 校验次数/Token 上限）
 *
 * <p>【口径】普通用户低配、按等级放开、VIP 高额度非不限量；等级/VIP 的放开系数随板块二
 * 生成端点接线时接入。所有上限 ≤0 表示未定档，仅计量不拦截（Q39 数值待定档）。</p>
 *
 * <p>【配置】前缀 litisle.ai.quota，按用途分组。</p>
 */
@Data
@ConfigurationProperties(prefix = "litisle.ai.quota")
public class AiQuotaProperties {

    /** 文本机审配额（机审无发起用户，预留占位不参与拦截） */
    private Limit moderation = new Limit();

    /** 摘要配额（SUMMARY） */
    private Limit summary = new Limit();

    /** 标签配额（TAGGING） */
    private Limit tagging = new Limit();

    /** 问答配额（QA） */
    private Limit qa = new Limit();

    /** 润色配额（POLISH，板块三 3.3） */
    private Limit polish = new Limit();

    /** 结构分析配额（STRUCTURE，板块三 3.3） */
    private Limit structure = new Limit();

    /**
     * 单用途日配额上限（次数与 Token 双维度，任一超限即拒绝）
     */
    @Data
    public static class Limit {

        /** 日调用次数上限（≤0=未定档不拦截） */
        private int dailyCalls = 0;

        /** 日 Token 总量上限（输入+输出合计；≤0=未定档不拦截） */
        private long dailyTokens = 0;
    }
}
