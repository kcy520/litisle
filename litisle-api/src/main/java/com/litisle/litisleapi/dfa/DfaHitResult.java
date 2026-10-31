package com.litisle.litisleapi.dfa;


import java.util.List;

/**
 * DFA 同步闸门判定结果
 *
 * <p>【消费纪律（发布链路调用方必读）】hit=true 且 maxLevel=1（HIGH 黑名单词）→
 * 同步拦截，错误码 70001（仅用于 HIGH）；hit=true 且 maxLevel=2（MID）→ 不拦截，
 * 只标记 layer1_hit=2 随内容送审（MID 亦作新增词灰度位，§2.5）；hit=false 放行</p>
 *
 * <p>hitWords 为命中的<b>词库规范词</b>（批次2 ③ 选型 A：审计证据=命中哪个黑名单词；
 * 变体 pattern 命中已在引擎内回映规范词，归一片段不回映原文——⓿→0/⑩→10 等宽化使
 * 归一位置≠原文位置，回映需归一→原文索引映射，复杂度不值），随 review_task/
 * moderation_record 留痕；wordlibVer 为判定时词库版本快照（缓存兜底比对防热更窗口旧结论放行）</p>
 *
 * @param hit        是否命中敏感词
 * @param maxLevel   最严命中级别 code（1-HIGH/2-MID，取最小 code；未命中为 null）
 * @param hitWords   命中词列表（去重）
 * @param wordlibVer 判定时词库版本快照
 */
public record DfaHitResult(boolean hit, Integer maxLevel, List<String> hitWords, long wordlibVer) {

    public static DfaHitResult noHit(long wordlibVer) {
        // 未命中：maxLevel=null、hitWords=空列表；版本快照仍必带（调用方留痕/缓存比对用）
        return new DfaHitResult(false, null, List.of(), wordlibVer);
    }
}