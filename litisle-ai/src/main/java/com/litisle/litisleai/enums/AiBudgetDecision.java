package com.litisle.litisleai.enums;

/**
 * 预算判定结论（D15 分账：判定读 ai_usage_daily 当日合计，本地缓存 60 秒）
 *
 * <p>执行语义由调用方区分：EXCEEDED 只停该用途新请求——生成类（SUMMARY/TAGGING/QA）
 * 停用并返回明确错误码；机审桶（MODERATION_TEXT/IMAGE 合并）不停调用，转全量人工 + 告警。</p>
 */
public enum AiBudgetDecision {

    /** 允许（用量 < 80% 预算） */
    ALLOWED,

    /** 预警（≥80% 预算，只提醒不停用） */
    WARN,

    /** 超限（100% 预算，只停该用途新请求） */
    EXCEEDED,
}
