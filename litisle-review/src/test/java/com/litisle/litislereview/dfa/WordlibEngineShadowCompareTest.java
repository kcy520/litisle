package com.litisle.litislereview.dfa;

import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.litisle.litisleapi.dfa.TextScanEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双引擎对照回归测试（批次3 ⑥ 影子比对，⑤b 删 houbb 的前置门槛）
 *
 * <p>【比对口径】同一测试词库分别构建 houbb（复刻旧生产六开关配置）与自研 AC 引擎，
 * 对同一批文本比对「命中/未命中」布尔结果——houbb findAll 返回变体原文片段、
 * AC 返回词库规范词，词面不做逐一对齐（归因靠布尔差集 + 定位用例完成）</p>
 *
 * <p>【差异归因登记（2026-10-30 探针实证，批次3 ⑥）】两引擎存在 6 处语义差异，
 * 全部为「AC 更严」方向（houbb 漏审而 AC 命中），零「AC 漏审」方向差异——
 * 对照通过（宁严勿松守住），⑤b 删 houbb 放行。根因唯一：houbb ignoreRepeat=true
 * 把文本相邻重复字符折叠后与词库原形失配——探针实证含重复字符的词**连精确原形文本
 * 都漏审**（text=谢谢 对词库 谢谢 repeatON=[] repeatOFF=[谢谢]），即旧生产配置对
 * 含重复字符的词系统性漏审（原形+一切重复变体）；AC 文本侧永不折叠 +
 * 构建期长形式变体 → 正常命中，修正该漏审。其余开关（大小写/全半角/数字写法/
 * 字母序号/繁简双向/allow 区间过滤）经探针实证两引擎语义一致</p>
 *
 * <p>【用例覆盖】六开关等价场景（大小写/全半角/数字写法/字母序号/繁简）+ 叠词正反样本
 * （谢谢/谢谢谢 命中，单字「谢」「天」不误命中——验证变体只长不短）+ 词库繁体/全角词样本
 * + 词内插重复拆写 + allow 白名单区间（houbb allow 区间过滤语义经探针实证与 AC 一致）+
 * 极端多位置重复（两引擎同漏的残余风险，异步机审 LLM 层兜底）</p>
 *
 * <p>【差异纪律】差异用例全部在本测试中以期望值钉死：引擎行为改动导致本测试变红时，
 * 必须先在清单登记归因再改期望值；AC 已追平的差异用例应转为常规用例并从差异清单移除</p>
 */
class WordlibEngineShadowCompareTest {

    /** 测试词库·黑名单：覆盖简体/繁体词源/叠词/四字词/字母数字混排/allow 子串词 */
    private static final List<String> DENY_WORDS = List.of(
            "谢谢",       // 叠词：验证重复变体只长不短
            "好好学习",   // 四字：词内插重复拆写主用例
            "杀人",       // 简体词源：繁体文本命中
            "殺人",       // 繁体词源：词库侧 pattern 归一化（简体文本命中繁体词）
            "赌博",
            "abc12",      // 字母数字混排：大小写/全角/序号变体
            "荣耀"        // allow 覆盖关系的子串词
    );

    /** 测试词库·白名单：deny 子串词的完整词（区间覆盖过滤用例） */
    private static final List<String> ALLOW_WORDS = List.of("王者荣耀");

    /** houbb 基线引擎（复刻旧生产 DfaFacade 六开关配置，批次2 ③ 前的行为基线） */
    private static SensitiveWordBs houbbBs;

    /** AC 引擎（批次2 ③ 新实现） */
    private static TextScanEngine acEngine;

    /**
     * 影子比对用例：expectHoubb/expectAc 分列——两引擎语义差异在此显式钉死
     *
     * @param desc           用例说明（差异归因入口）
     * @param text           待测文本
     * @param expectHoubb    houbb 期望命中
     * @param expectAc       AC 期望命中
     * @param documentedDiff true=两引擎语义差异用例（根因见类注释归因登记）；false=要求两引擎一致
     */
    private record Case(String desc, String text, boolean expectHoubb, boolean expectAc, boolean documentedDiff) {
    }

    private static final List<Case> CASES = List.of(
            // ---------- 基础命中/未命中（两引擎一致） ----------
            new Case("简体原形命中", "这里有人在赌博", true, true, false),
            new Case("正常文本未命中", "今天天气不错，适合散步", false, false, false),
            new Case("空文本未命中", "", false, false, false),
            // ---------- 六开关等价：大小写/全半角/数字写法/字母序号/繁简（两引擎一致） ----------
            new Case("大小写变体命中", "口令是AbC12没错", true, true, false),
            new Case("全角变体命中", "口令是ＡＢＣ１２没错", true, true, false),
            new Case("字母序号变体命中", "口令是ⒶⒷⒸ１２没错", true, true, false),
            new Case("圆圈数字变体命中", "口令是ⓐⓑⓒ①②没错", true, true, false),
            new Case("大小写全角混合变体命中", "口令是ＡｂＣ１２没错", true, true, false),
            new Case("繁体文本命中简体词", "我要殺人", true, true, false),
            new Case("全角标点夹带未命中", "网址是ＡＢＣ。１２？不", false, false, false),
            // ---------- 叠词正反样本：3 例 AC 更严差异（根因 A：houbb ignoreRepeat 折叠失配） ----------
            new Case("叠词原形命中（AC 更严）", "谢谢", false, true, true),
            new Case("叠词加长命中（AC 更严）", "谢谢谢", false, true, true),
            new Case("叠词再加长命中（AC 更严）", "谢谢谢谢", false, true, true),
            // 反面：单字/词中单字不误命中（折叠短形式禁注册，两引擎一致）
            new Case("单字谢不误命中（折叠短形式禁注册）", "谢", false, false, false),
            new Case("单字天不误命中", "天", false, false, false),
            new Case("词中单字不误命中", "感谢帮助", false, false, false),
            // ---------- 词内插重复拆写：3 例 AC 更严差异（根因 A） ----------
            new Case("词内插重复×2命中（AC 更严）", "好好学学习", false, true, true),
            new Case("词内插重复×3命中（AC 更严）", "好好学学学习", false, true, true),
            new Case("词尾插重复命中（AC 更严）", "好好学习习", false, true, true),
            // 两引擎同漏（F/F）：houbb 折叠失配同因；AC 受变体展开预算边界（每字符额外≤2、总插入≤3）；
            // 残余风险由异步机审 LLM 层兜底（批次2 ③ 已登记）
            new Case("极端多位置重复（两引擎同漏）", "好好好好好学学学学学学习", false, false, false),
            // 两引擎一致（houbb 词侧同样过繁简归一，实证：简体文本对繁体词源 houbb 亦命中）
            new Case("简体文本命中繁体词源", "我要杀人", true, true, false),
            // ---------- allow 白名单区间（houbb allow 语义经探针实证与 AC 一致） ----------
            new Case("allow 完整词区间过滤 deny 子串", "王者荣耀很好玩", false, false, false),
            new Case("allow 词不存在时 deny 正常命中", "这个荣耀真垃圾", true, true, false),
            new Case("deny 单独出现命中", "荣耀", true, true, false),
            // ---------- 归一化不越权（乱序/拆字不误命中） ----------
            new Case("字符乱序不命中", "口令是a1b2c3没错", false, false, false)
    );

    /**
     * 构建双引擎基线：houbb 复刻旧生产六开关配置；AC 走 TextScanEngine SPI
     */
    @BeforeAll
    static void buildEngines() {
        houbbBs = SensitiveWordBs.newInstance()
                .wordDeny(() -> DENY_WORDS)
                .wordAllow(() -> ALLOW_WORDS)
                .ignoreCase(true)
                .ignoreWidth(true)
                .ignoreNumStyle(true)
                .ignoreChineseStyle(true)
                .ignoreEnglishStyle(true)
                .ignoreRepeat(true)
                .init();
        acEngine = TextScanEngine.build(DENY_WORDS, ALLOW_WORDS);
    }

    @Test
    @DisplayName("影子比对：全部用例两引擎结果与期望一致（documented 差异用例单独钉死）")
    void shadowCompareAllCases() {
        for (Case c : CASES) {
            boolean houbbHit = !houbbBs.findAll(c.text()).isEmpty();
            boolean acHit = !acEngine.scan(c.text()).isEmpty();
            // houbb 基线自检（基线自身偏离期望=测试词库/配置理解有误，先修基线）
            assertEquals(c.expectHoubb(), houbbHit,
                    "[houbb 基线偏离] " + c.desc() + " text=" + c.text());
            // AC 结果自检
            assertEquals(c.expectAc(), acHit,
                    "[AC 偏离] " + c.desc() + " text=" + c.text());
            // 一致性：非 documented 差异用例要求两引擎同命中态（影子比对本体）
            if (!c.documentedDiff()) {
                assertEquals(houbbHit, acHit,
                        "[影子差异] " + c.desc() + " text=" + c.text()
                                + " houbb=" + houbbHit + " ac=" + acHit);
            }
        }
    }

    @Test
    @DisplayName("差异清单核对：documented 差异必须保持「AC 更严」方向（houbb 漏审、AC 命中）")
    void documentedDiffCasesStayPinned() {
        for (Case c : CASES.stream().filter(Case::documentedDiff).toList()) {
            boolean houbbHit = !houbbBs.findAll(c.text()).isEmpty();
            boolean acHit = !acEngine.scan(c.text()).isEmpty();
            // 差异方向钉死：houbb 未命中、AC 命中（AC 更严方向）；
            // 若出现反向（AC 漏审）立即失败——宁严勿松红线的守门断言
            assertTrue(!houbbHit && acHit,
                    "[差异清单漂移] " + c.desc() + " houbb=" + houbbHit + " ac=" + acHit
                            + "（AC 漏审方向差异=不可接受；AC 已追平则转常规用例）");
        }
    }
}
