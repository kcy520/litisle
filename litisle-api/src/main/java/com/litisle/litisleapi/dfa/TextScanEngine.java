package com.litisle.litisleapi.dfa;

import java.util.List;

/**
 * 同步闸门文本扫描引擎 SPI（批次2 ③：替代 houbb SensitiveWordBs 的引擎抽象）
 *
 * <p>职责边界：只回答「文本里有没有词库里的词」，不回答「该不该拦截」——
 * 词库来自 {@link WordLibraryLoader}，级别分类与拦截策略属于调用方（DfaFacade/DfaPublishGate）</p>
 *
 * <p>【引擎语义契约（防绕过红线，实现方必须遵守）】</p>
 * <ul>
 *   <li><b>归一化双向分治</b>：文本侧只做无损宽化归一（{@link CharNormalizer}，永不折叠重复）；
 *       有损折叠（重复字符 N:1）只允许以「构建期展开为变体 pattern」的方式进 trie（见下）</li>
 *   <li><b>重复变体只长不短</b>：deny 词在构建期展开为「重复长形式」变体（长度≥原词，
 *       如 谢谢→谢谢谢/谢谢学学习 类同字符加倍形式），对抗在词中间插入重复字符的拆写绕过
 *       （好好学习 → 好好学学习 原词不再是子串）；<b>禁注册折叠短形式</b>
 *       （把 谢谢 折叠成 谢 注册会让正常单字全误报）；展开必须有界（每字符额外重复上限
 *       + 每词变体总数封顶），超限退化为仅原形并告警</li>
 *   <li><b>词库侧 pattern 同过归一化</b>：trie 注册的全部 pattern（含变体展开结果）必须先经
 *       {@link CharNormalizer#normalize}——漏掉此步则繁体/全角词以原始形式进 trie，
 *       而文本侧已全部归一，繁简/全半角对抗静默失效（变形词全部漏审）</li>
 *   <li><b>命中裁决</b>：allow 命中优先——deny 命中区间完全落在任一 allow 命中区间内则剔除
 *       （覆盖嵌套与部分重叠）；deny 命中去重 + 最长优先——短命中区间被更长 deny 命中
 *       完全覆盖时只报长命中（AB/B 同库，text=AB 只报 AB）</li>
 *   <li><b>hitWords 留痕语义（选型 A）</b>：返回词库<b>规范词</b>（审计证据=命中哪个黑名单词）；
 *       变体 pattern 命中由引擎内部回映到规范词，归一片段不回映原文
 *       （⓿→0/⑩→10 等宽化使归一位置≠原文位置，回映需归一→原文索引映射，复杂度不值）</li>
 * </ul>
 *
 * <p>线程安全：构建完成后不可变（build 产物只读），scan 可并发调用；
 * 热更由 DfaFacade 整体重建新实例后原子换引用</p>
 */
public interface TextScanEngine {

    /**
     * 从词表构建引擎（deny/allow 传词库原始词，构建期内部完成归一化 + deny 变体展开 + AC 自动机构建）
     *
     * @param denyWords 黑名单词（敏感词，命中即证据）
     * @param allowWords 白名单词（放行词，优先级高于黑名单）
     * @return 可并发扫描的引擎实例
     */
    static TextScanEngine build(List<String> denyWords, List<String> allowWords) {
        // 当前唯一实现：hankcs ACDAT 双数组 trie（DFA 引擎切换批次2 接线）；
        // 旧自研 AcTextScanEngine 已于批次3 影子比对归因归零后删除（2026-11-01）
        return AcdatTextScanEngine.build(denyWords, allowWords);
    }

    /**
     * 全量扫描文本，返回命中的词库规范词列表（已按引擎语义契约完成 allow 过滤、
     * 最长优先去重与变体回映；调用方拿到的即为可留痕的规范词）
     *
     * @param text 待扫描文本（null 安全）
     * @return 命中规范词去重列表（未命中为空列表，绝不返回 null）
     */
    List<String> scan(String text);
}
