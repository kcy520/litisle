package com.litisle.litisleapi.dfa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC 扫描引擎语义契约测试（批次2 ③）：覆盖归一化分治、重复变体只长不短、
 * allow 优先过滤、最长优先去重、变体回映规范词等防绕过红线行为
 */
class AcTextScanEngineTest {

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
}
