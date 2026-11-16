package com.litisle.litisleapi.dto.ai;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * AI 调用用途打标枚举（计量与预算分账的唯一定义，D15 分账口径）
 *
 * <p>【口径】计费与预算只认生成与机审类（OTHER 除外）；OTHER 仅兜底落明细、不计入预算与
 * 报表，出现即按「未归类调用」告警观察。用途由调用方显式传入，禁止按 URL / 模型名 / 表名推断。
 * 2026-11-15 板块三扩展：新增 POLISH / STRUCTURE（润色/结构分析生成用途，随板块三 3.3 落地，
 * 各自独立预算桶——对齐 D15「生成类各自独立桶」精神；D15 原文仅列前五类，文档口径修订随
 * 板块三回写）。2026-11-15 板块五扩展：新增 SIMILAR（相似文章推荐，F8 纯检索形态零生成成本，
 * 仍落一条 token=0 明细保证可观测）。</p>
 *
 * <p>【分账桶】机审桶 = MODERATION_TEXT + MODERATION_IMAGE 合并；
 * SUMMARY / TAGGING / QA / POLISH / STRUCTURE / SIMILAR 各自独立桶。桶的合并逻辑由 litisle-ai 的
 * 预算服务承担，本枚举只提供用途定义与计费标记。</p>
 *
 * <p>【序列化口径（与展示型枚举不同）】实现 DictEnum 获得 name/code/desc 三值兼容绑定；
 * 但 @JsonValue 标在 code 而非 desc——本枚举是 review↔ai 的 Feign 传输契约，
 * JSON 线上格式必须为机器编码（MODERATION_TEXT 等），不随展示文案（desc）漂移；
 * 与 ai_token_usage/ai_usage_daily.purpose 的 @EnumValue 入库值同源（均取 code）。</p>
 *
 * @author litisle
 */
@Getter
public enum AiPurposeEnum implements DictEnum {

    /** 文本机审（review 经 AiClient 转发，走机审桶） */
    MODERATION_TEXT("MODERATION_TEXT", "文本机审"),

    /** 图片机审（AI 图片判定端点实现后接入，走机审桶） */
    MODERATION_IMAGE("MODERATION_IMAGE", "图片机审"),

    /** 摘要生成（小悦，独立预算桶） */
    SUMMARY("SUMMARY", "摘要生成"),

    /** 标签建议（小悦，独立预算桶） */
    TAGGING("TAGGING", "标签建议"),

    /** 站内问答（RAG，独立预算桶） */
    QA("QA", "站内问答"),

    /** 润色建议（小悦，板块三 3.3，独立预算桶） */
    POLISH("POLISH", "润色建议"),

    /** 结构分析（小悦，板块三 3.3，独立预算桶） */
    STRUCTURE("STRUCTURE", "结构分析"),

    /** 相似文章推荐（板块五 5.1，F8 纯检索形态：独立预算桶，纯检索 token=0 零生成成本） */
    SIMILAR("SIMILAR", "相似文章推荐"),

    /** 未归类兜底：仅落明细不计费不计预算，出现即告警 */
    OTHER("OTHER", "未归类调用"),
    ;

    /** 用途编码：JSON 序列化值（@JsonValue）；DB 入库值（@EnumValue：MybatisEnumTypeHandler 按此值与 ai_token_usage/ai_usage_daily.purpose 互转） */
    @JsonValue
    @EnumValue
    private final String code;

    /** 用途说明 */
    private final String desc;

    AiPurposeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 判断该用途是否计入计费与预算（机审/生成类 true，OTHER false）
     *
     * @return true=计入计费与预算；false=仅兜底落明细
     */
    public boolean isMetered() {
        return this != OTHER;
    }

    /**
     * 根据 code 反查枚举，找不到返回 null
     *
     * @JsonCreator：接管反序列化，未知编码返回 null 而非抛异常，
     * 由端点侧按「purpose 缺省拒绝并告警」口径统一处理
     *
     * @param code 用途编码（如 MODERATION_TEXT）
     * @return 匹配的枚举常量，未匹配返回 null
     */
    @JsonCreator
    public static AiPurposeEnum fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AiPurposeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
