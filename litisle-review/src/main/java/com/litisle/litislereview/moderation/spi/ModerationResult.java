package com.litisle.litislereview.moderation.spi;

import com.litisle.litislereview.enums.ModerationCategoryEnum;
import com.litisle.litislereview.enums.VerdictEnum;

/**
 * 机审核定结果（SPI 门面统一出参，与厂商无关的翻译后产物）
 *
 * <p>厂商类目体系差异（politics/porn/abuse/ad）已在适配器内映射为
 * {@link ModerationCategoryEnum}，不外溢；category/reason 来自模型输出，属不可信数据，
 * 适配器落包前须完成长度截断与白名单枚举校验，回显侧再按 XSS 规则转义</p>
 *
 * @param verdict         三分态判定（仅 PASS/REJECT/SUSPICIOUS，NONE 不应出现）
 * @param category        违规类目（机审统一映射枚举；PASS 时为 NONE）
 * @param reason          判定理由（LLM 输出透传；作者申诉与管理员复审依据）
 * @param providerTraceId 审核凭证 ID（云厂商 traceId / LLM 请求 ID，合规出证；
 *                        本地组件为 null）
 * @param costMs          审核耗时（观测 Timer 旁路佐证）
 */
public record ModerationResult(VerdictEnum verdict,
                               ModerationCategoryEnum category,
                               String reason,
                               String providerTraceId,
                               long costMs) {
}