package com.litisle.litisleapi.dfa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫描引擎语义契约测试（批次2 ③ 引入，DFA 引擎切换批次3 收口改造）：
 * 覆盖归一化分治、重复变体只长不短、allow 优先过滤、最长优先去重、变体回映规范词等防绕过红线行为；
 * 内核边界核对①②③用例（端点口径/重叠完整性/代理对）保留为回归守护——批次2 曾以「双引擎断言」
 * 完成 hankcs ACDAT 与旧自研 AC 的影子核对，批次3 影子比对归因归零删除旧引擎后，本类收敛为
 * 面向 TextScanEngine SPI（hankcs ACDAT 唯一实现）的单引擎契约测试，类名沿用历史不改
 */
class AcTextScanEngineTest {

    /**
     * 引擎实例构建：经 TextScanEngine SPI 工厂构建引擎（批次3 后工厂唯一指向 hankcs ACDAT 实现）
     *
     * @param deny  黑名单词表
     * @param allow 白名单词表
     * @return 构建完成的扫描引擎实例
     */
    private static TextScanEngine newEngine(List<String> deny, List<String> allow) {
        return TextScanEngine.build(deny, allow);
    }


    @Test
    @DisplayName("CharNormalizer：大小写/全半角/数字写法/字母序号/繁简 各维度归一")
    void normalizerCoversAllDimensions() {
        // 大小写折叠
        assertEquals("abc", CharNormalizer.normalize("AbC"));
        // 全角→半角（字母/数字/标点）
        assertEquals("ab12!", CharNormalizer.normalize("ＡＢ１２！"));
        // 数字写法：⓿→0（1:1）、①→1（1:1）、⑩→10（1:N 展开）
        assertEquals("0110", CharNormalizer.normalize("⓿①⑩"));
        // 字母序号：Ⓐⓑ→ab
        assertEquals("ab", CharNormalizer.normalize("Ⓐⓑ"));
        // 繁→简（opencc4j 同源口径）
        assertEquals("杀人", CharNormalizer.normalize("殺人"));
        // 无损宽化不折叠重复：叠词原样保留（红线：文本侧永不折叠）
        assertEquals("谢谢", CharNormalizer.normalize("谢谢"));
    }

    @Test
    @DisplayName("基础命中：deny 词命中返回规范词，正常文本无命中")
    void basicDenyHit() {
        TextScanEngine engine = TextScanEngine.build(List.of("谢谢"), List.of());
        assertEquals(List.of("谢谢"), engine.scan("你好谢谢啊"));
        assertTrue(engine.scan("正常评论内容").isEmpty());
    }

    @Test
    @DisplayName("归一化命中：全角/大写/繁体文本命中半角/小写/简体词")
    void normalizedTextHitsCanonicalWord() {
        TextScanEngine engine = TextScanEngine.build(List.of("ab12"), List.of());
        // 全角＋大写文本 → 归一后命中
        assertEquals(List.of("ab12"), engine.scan("ＡＢ１２"));
        // 繁体文本命中简体词（词库侧 pattern 同过归一化的对偶验证）
        TextScanEngine engine2 = TextScanEngine.build(List.of("杀人"), List.of());
        assertEquals(List.of("杀人"), engine2.scan("我要殺人"));
    }

    @Test
    @DisplayName("重复变体只长不短：词中间插重复字符的拆写仍命中，且回映规范词")
    void repeatVariantHitsAndMapsBackToCanonical() {
        TextScanEngine engine = TextScanEngine.build(List.of("好好学习"), List.of());
        // 拆写绕过：学 被插入重复 → 原词不再是子串，靠构建期变体 pattern 命中
        assertEquals(List.of("好好学习"), engine.scan("好好学学习"));
        assertEquals(List.of("好好学习"), engine.scan("好好学学学习"));
        // 词尾扩展重复（文本比词重复更多）也能命中
        assertEquals(List.of("好好学习"), engine.scan("好好学习习"));
        // 文本侧永不折叠：正常叠词不受影响
        assertTrue(engine.scan("今天要好好学习").contains("好好学习"));
    }

    @Test
    @DisplayName("allow 优先：deny 命中完全落在 allow 命中区间内被剔除")
    void allowIntervalFiltersDenyHit() {
        TextScanEngine engine = TextScanEngine.build(List.of("cd"), List.of("abcd"));
        // "abcd" 中 cd 完全落在 abcd 区间内 → 放行
        assertTrue(engine.scan("abcd").isEmpty());
        // 独立出现仍命中
        assertEquals(List.of("cd"), engine.scan("xcdy"));
    }

    @Test
    @DisplayName("最长优先：短 deny 命中区间被更长 deny 命中完全覆盖时只报长命中")
    void longestMatchWins() {
        TextScanEngine engine = TextScanEngine.build(List.of("AB", "B"), List.of());
        // AB 与 B 同库，text=AB 只报 AB
        assertEquals(List.of("AB"), engine.scan("AB"));
    }

    @Test
    @DisplayName("多词命中：多个不同规范词同时返回")
    void multipleDistinctHits() {
        TextScanEngine engine = TextScanEngine.build(List.of("暴力", "赌博"), List.of());
        List<String> hits = engine.scan("内容涉及暴力与赌博");
        assertEquals(2, hits.size());
        assertTrue(hits.contains("暴力"));
        assertTrue(hits.contains("赌博"));
    }

    @Test
    @DisplayName("空文本/空词库：安全返回空结果不抛异常")
    void emptyInputsAreSafe() {
        TextScanEngine engine = TextScanEngine.build(List.of("谢谢"), List.of());
        assertTrue(engine.scan("").isEmpty());
        assertTrue(engine.scan(null).isEmpty());
        TextScanEngine empty = TextScanEngine.build(List.of(), List.of());
        assertTrue(empty.scan("任意文本").isEmpty());
    }

    // ==================== 内核边界核对①②③（DFA 引擎切换批次2，双引擎断言守护） ====================

    @Test
    @DisplayName("内核边界①：allow 区间裁决端点口径——右端等界完全落入剔除；首尾相接仅部分重叠不误剔")
    void kernelBoundaryAllowIntervalEndpoints() {
        // 右端等界：cd 命中区间右端与 abcd 区间右端等界，属「完全落入」→ 剔除
        TextScanEngine endpoints = newEngine(List.of("cd"), List.of("abcd"));
        assertTrue(endpoints.scan("abcd").isEmpty());
        // 首尾相接：bc 与 ab/cd 两个 allow 区间仅部分重叠（非完全落入）→ 不剔除，仍命中
        TextScanEngine adjacency = newEngine(List.of("bc"), List.of("ab", "cd"));
        assertEquals(List.of("bc"), adjacency.scan("abcd"));
    }

    @Test
    @DisplayName("内核边界②：嵌套/重叠命中完整性——AB/B 只报 AB；AB/BC 部分重叠都报；同词多处命中去重")
    void kernelBoundaryOverlapCompleteness() {
        // AB 与 B 同库，text=AB 只报 AB（B 被 AB 完全覆盖；依赖 ACDAT 返回嵌套命中作裁决原料）
        TextScanEngine nested = newEngine(List.of("AB", "B"), List.of());
        assertEquals(List.of("AB"), nested.scan("AB"));
        // AB [0,2) 与 BC [1,3) 部分重叠非包含 → 都保留（都算真实证据）
        TextScanEngine overlap = newEngine(List.of("AB", "BC"), List.of());
        List<String> hits = overlap.scan("ABC");
        assertEquals(2, hits.size());
        assertTrue(hits.contains("AB"));
        assertTrue(hits.contains("BC"));
        // 同一规范词多处/重叠命中只报一次（同词去重）
        TextScanEngine repeated = newEngine(List.of("aa"), List.of());
        assertEquals(List.of("aa"), repeated.scan("aaaa"));
    }

    @Test
    @DisplayName("内核边界③：emoji 增补平面代理对——按 char 索引逐字符处理，打断不命中、邻接正常命中")
    void kernelBoundarySurrogatePairs() {
        // 😀 为增补平面字符，UTF-16 编码占 2 个 char（代理对）
        String emoji = "\uD83D\uDE00";
        // emoji 打断 a、b → ab 不连续（代理对占位）→ 不命中（若按码点合并处理会误命中）
        TextScanEngine interrupted = newEngine(List.of("ab"), List.of());
        assertTrue(interrupted.scan("a" + emoji + "b").isEmpty());
        // emoji 在词外邻接 → 正常命中
        assertEquals(List.of("ab"), interrupted.scan(emoji + "ab" + emoji));
        // 词中插 emoji → 变体只展开重复字符不覆盖 emoji 占位 → 不命中
        TextScanEngine variant = newEngine(List.of("好好学习"), List.of());
        assertTrue(variant.scan("好" + emoji + "好学学习").isEmpty());
    }

    @Test
    @DisplayName("同形注册语义：deny 与 allow 归一形相同 → deny 优先，allow 不提供保护")
    void sameFormDenyWinsOverAllow() {
        // deny=谢谢 与 allow=谢谢 同形：deny 先注册，allow 跳过 → 仍命中
        TextScanEngine identical = newEngine(List.of("谢谢"), List.of("谢谢"));
        assertEquals(List.of("谢谢"), identical.scan("谢谢"));
        // 繁体 allow 归一后与简体 deny 同形 → deny 优先（归一形占用判定）
        TextScanEngine traditional = newEngine(List.of("杀人"), List.of("殺人"));
        assertEquals(List.of("杀人"), traditional.scan("殺人"));
    }
}
