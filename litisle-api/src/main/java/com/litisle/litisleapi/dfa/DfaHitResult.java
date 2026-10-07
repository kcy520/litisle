package com.litisle.litisleapi.dfa;


import java.util.List;

/**
 * DFA 同步闸门判定结果
 *
 * <p>【消费纪律（发布链路调用方必读）】hit=true 且 maxLevel=1（HIGH 黑名单词）→
 * 同步拦截，错误码 70001（仅用于 HIGH）；hit=true 且 maxLevel=2（MID）→ 不拦截，
 * 只标记 layer1_hit=2 随内容送审（MID 亦作新增词灰度位，§2.5）；hit=false 放行</p>
 *
 * <p>hitWords 为命中的词快照（houbb 变体识别归一后的原文片段），随 review_task/
 * moderation_record 留痕；wordlibVer 为判定时词库版本快照（缓存兜底比对防热更窗口旧结论放行）</p>
 *
 * @param hit        是否命中敏感词
 * @param maxLevel   最严命中级别 code（1-HIGH/2-MID，取最小 code；未命中为 null）
 * @param hitWords   命中词列表（去重）
 * @param wordlibVer 判定时词库版本快照
 */
public record DfaHitResult(boolean hit, Integer maxLevel, List<String> hitWords, long wordlibVer) {

    public static DfaHitResult noHit(long wordlibVer) {

        return new DfaHitResult(false, null, List.of(), wordlibVer);
    }
}