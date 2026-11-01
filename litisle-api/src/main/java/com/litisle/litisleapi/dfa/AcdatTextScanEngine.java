package com.litisle.litisleapi.dfa;

import com.hankcs.algorithm.AhoCorasickDoubleArrayTrie;
import com.hankcs.algorithm.AhoCorasickDoubleArrayTrie.Hit;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * hankcs ACDAT 扫描引擎（TextScanEngine 的唯一生产实现，2026-11-01 DFA 引擎切换批次2 接线、
 * 批次3 收口生效）：由自研朴素 trie + fail 指针 AC 自动机切换为 hankcs 双数组 trie 内核，
 * 引擎语义契约与命中裁决代码原样平移，宿主调用方（仅依赖 DfaFacade）零波及
 *
 * <p>内核 = hankcs AhoCorasickDoubleArrayTrie（AC 自动机的双数组 trie 存储，严格 O(文本长度)，
 * 官方基准英文 5 倍 / 中文 9 倍于朴素实现）。引擎语义契约（归一化双向分治 / deny 变体只长不短 /
 * pattern 同过归一化 / allow 完全落入才剔除 / deny 最长优先去重 / hitWords 规范词留痕）原样保留，
 * 契约全文见 {@link TextScanEngine} 类注释，本类为实现方</p>
 *
 * <p>【两条流水线】</p>
 * <ul>
 *   <li>构建期 {@link #build}（词库变更时执行一次）：deny/allow 词逐个经 {@link CharNormalizer}
 *       归一化 → deny 词回溯展开重复变体（只长不短，三重封顶超限退化为仅原形）→ pattern 首见
 *       生效去重 → deny pattern 以「词库规范词」为载荷、allow 归一形独立成表 → 分别构建 ACDAT
 *       双数组 trie</li>
 *   <li>扫描期 {@link #scan}（每次送审内容调用）：文本归一化 → ACDAT 全量匹配 → allow 区间裁决
 *       （deny 命中完全落入 allow 区间才剔除）→ deny 最长优先去重 → Hit 载荷即规范词直接回映 →
 *       返回去重保序的规范词列表</li>
 * </ul>
 *
 * <p>【与旧引擎的结构差异（语义等价，仅换内核）】旧引擎用「pattern 数组 + canonicalWords 下标
 * 回映表」，本类把规范词直接写进 ACDAT 构建载荷（TreeMap：pattern → 规范词），命中 Hit.value
 * 即规范词，省去下标对齐；allow 归一形独立建表，不与 deny 混在同一 trie。同形注册语义平移：
 * deny 变体先全部注册，allow 词归一形若已被 deny 占用则跳过（deny 优先，allow 不提供保护——
 * 与旧引擎共享 seen 注册表、deny 先注册的结果一致）</p>
 *
 * <p>线程安全：双数组 trie 构建完成后不可变只读，scan 可并发调用；热更仍由 DfaFacade
 * 整体重建新实例后原子换引用，WordlibSnapshot 快照语义零改动</p>
 */
@Slf4j
public final class AcdatTextScanEngine implements TextScanEngine {

    /**
     * 变体展开·每字符额外重复次数上限（原字符 1 次 + 额外最多 2 次）：
     * 覆盖常见拆写（好好学习→好好学学习/好好学学学习），更高次数收益趋零
     */
    private static final int MAX_EXTRA_REPEATS = 2;

    /**
     * 变体展开·全词总插入重复字符数上限：限制同时加重的位置组合数，
     * 变体总量从 3^L 指数级压到 O(L^3) 多项式级（真正的组合爆炸防线）
     */
    private static final int MAX_TOTAL_EXTRA_REPEATS = 3;

    /**
     * 变体展开·每词变体总数封顶：超限整词退化为仅原形 + 告警
     * （宁漏不长尾拖垮 trie；封顶覆盖不到的极端拆写由异步机审 LLM 层兜底）
     */
    private static final int MAX_VARIANTS_PER_WORD = 128;

    /** deny 自动机：pattern（归一化+变体展开）→ 词库规范词（原始业务词），命中后 Hit.value 直接带回规范词 */
    private final AhoCorasickDoubleArrayTrie<String> denyTrie;

    /** allow 自动机：归一化 allow 原形 → 自身（载荷不参与裁决，裁决只用命中区间） */
    private final AhoCorasickDoubleArrayTrie<String> allowTrie;

    /**
     * 私有构造：仅由 {@link #build} 工厂调用，打包两棵不可变 ACDAT 为引擎实例
     *
     * @param denyTrie  deny 自动机（黑名单 pattern → 规范词；空黑名单为 null）
     * @param allowTrie allow 自动机（白名单归一形；空白名单为 null）
     */
    private AcdatTextScanEngine(AhoCorasickDoubleArrayTrie<String> denyTrie,
                                AhoCorasickDoubleArrayTrie<String> allowTrie) {
        this.denyTrie = denyTrie;
        this.allowTrie = allowTrie;
    }

    /**
     * 构建引擎：归一化 → deny 变体展开（有界）→ pattern 首见生效去重 → 两棵 ACDAT 构建
     *
     * <p>注册顺序平移旧引擎语义：deny 词全部先注册，allow 词后注册且归一形已被 deny 占用时跳过</p>
     *
     * @param denyWords  黑名单词（敏感词，命中即证据）
     * @param allowWords 白名单词（放行词，优先级低于 deny 同形占用）
     * @return 可并发扫描的引擎实例
     */
    static TextScanEngine build(List<String> denyWords, List<String> allowWords) {
        // deny 注册表：pattern（归一化+变体展开）→ 词库规范词；putIfAbsent 首见生效（同形 pattern 只注册一次，先到先得）
        TreeMap<String, String> denyMap = new TreeMap<>();
        // Step1 deny 词：归一化 → 变体展开 → 逐 pattern 注册并映射回规范词
        for (String word : denyWords) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            // 红线：词库侧 pattern 先过归一化（漏掉则繁体/全角词以原始形式进 trie，变形对抗静默失效）
            String normalized = CharNormalizer.normalize(word);
            // ACDAT 无法注册空串 pattern（产生零长区间误报），防御性跳过
            //（CharNormalizer 对非空输入实际不产出空串，此为兜底）
            if (normalized.isEmpty()) {
                log.warn("deny 词归一化后为空，跳过注册：word={}", word);
                continue;
            }
            // 逐变体注册：putIfAbsent 已存在（同形 pattern 注册过）则丢弃，防同形词重复建 trie 路径
            for (String variant : expandRepeatVariants(normalized, word)) {
                denyMap.putIfAbsent(variant, word);
            }
        }
        // allow 注册表：归一化原形 → 自身（不展开变体——放行词从严，变体形态不享受放行）
        TreeMap<String, String> allowMap = new TreeMap<>();
        // Step2 allow 词：仅归一化原形注册；归一形已被 deny 占用时跳过（deny 先注册=deny 优先，
        // 平移旧引擎共享 seen 注册表的等价结果）
        for (String word : allowWords) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            String normalized = CharNormalizer.normalize(word);
            // 同 deny：空串防御跳过
            if (normalized.isEmpty()) {
                log.warn("allow 词归一化后为空，跳过注册：word={}", word);
                continue;
            }
            // 已被 deny pattern 占用 → 跳过（等价旧引擎 seen.add 返回 false 的丢弃分支）
            if (denyMap.containsKey(normalized)) {
                continue;
            }
            // allow 之间同形去重：首见生效
            allowMap.putIfAbsent(normalized, normalized);
        }
        // Step3 两棵 ACDAT 构建（双数组内核：构建期一次性建好，扫描期只读）
        return new AcdatTextScanEngine(buildTrie(denyMap), buildTrie(allowMap));
    }

    /**
     * 由注册表构建 ACDAT（空表返回 null，扫描期按无自动机处理）
     *
     * @param map pattern → 载荷（TreeMap：键唯一且有序，ACDAT 构建的要求）
     * @return 构建完成的只读双数组 trie；空表为 null
     */
    private static AhoCorasickDoubleArrayTrie<String> buildTrie(TreeMap<String, String> map) {
        // 空词表不建 trie（空表构建无意义），扫描期以 null 判空短路
        if (map.isEmpty()) {
            return null;
        }
        AhoCorasickDoubleArrayTrie<String> trie = new AhoCorasickDoubleArrayTrie<>();
        trie.build(map);
        return trie;
    }

    /**
     * deny 词重复变体展开（构建期，只长不短——有损折叠的替代方案）：
     * 回溯式预算展开——每字符额外重复 0..{@value MAX_EXTRA_REPEATS} 次（消耗预算），
     * 全词总插入重复 ≤ {@value MAX_TOTAL_EXTRA_REPEATS}，变体数 ≤ {@value MAX_VARIANTS_PER_WORD}；
     * 超封顶整词退化为仅原形并告警。必含原形（各位置选 0 额外重复的分支最先走）
     *
     * @param normalized 归一化后的词（变体基于归一形展开，保证与文本侧归一口径对齐）
     * @param origin     原始词（仅告警日志定位用）
     * @return 变体 pattern 列表（必含原形，同形去重保序）
     */
    private static List<String> expandRepeatVariants(String normalized, String origin) {
        // LinkedHashSet：不同组合向量可能生成同串（如 好好→(2,0)/(1,1) 同为 好好好好），需去重
        Set<String> variants = new LinkedHashSet<>();
        // 触发回溯展开；返回 true = 变体数量超封顶，整词退化为仅原形
        boolean overflow = expandRec(normalized, 0, MAX_TOTAL_EXTRA_REPEATS,
                new StringBuilder(normalized.length() + MAX_TOTAL_EXTRA_REPEATS), variants);
        if (overflow) {
            log.warn("deny 词变体展开超封顶，退化为仅原形：word={}（归一形={}，变体上限={}）",
                    origin, normalized, MAX_VARIANTS_PER_WORD);
            // 退化兜底：只注册归一原形（宁漏不长尾拖垮 trie，极端拆写由异步机审 LLM 层兜底）
            return List.of(normalized);
        }
        // 不可变列表出参（防构建产物被上游误改）
        return List.copyOf(variants);
    }

    /**
     * 变体展开回溯：pos 处字符选重复 1..(1+额外) 次后递归下一位；
     * 返回 true=已超变体封顶（调用方整词退化为原形）
     *
     * @param normalized      只读，正在展开的归一形
     * @param pos             当前处理到的字符下标（0-based）
     * @param remainingBudget 剩余「额外插入字符」总预算（初始=MAX_TOTAL_EXTRA_REPEATS，逐层扣减）
     * @param sb              拼装中的变体草稿（递归中 append，返回前撤销恢复现场）
     * @param variants        变体收集桶（一种完整组合成形即收录）
     * @return true=变体数已达封顶（逐层短路）
     */
    private static boolean expandRec(String normalized, int pos, int remainingBudget,
                                     StringBuilder sb, Set<String> variants) {
        // 终点：一种组合向量成形
        if (pos == normalized.length()) {
            if (variants.size() >= MAX_VARIANTS_PER_WORD) {
                // 变体数到达封顶：逐层短路返回 true，调用方整词退化为仅原形
                return true;
            }
            // 收录当前累积出的完整变体串
            variants.add(sb.toString());
            return false;
        }
        // 本位字符与其最大额外重复次数（受单字符上限与剩余总预算双重约束）
        char c = normalized.charAt(pos);
        int maxExtra = Math.min(MAX_EXTRA_REPEATS, remainingBudget);
        // 枚举本位重复档位：extra=0 即原形档位（最先走，保证原形必在变体集中）
        for (int extra = 0; extra <= maxExtra; extra++) {
            int repeat = 1 + extra;
            // 选定本位重复 repeat 次后递归下一位（总预算同步扣减）
            sb.append(String.valueOf(c).repeat(repeat));
            boolean overflow = expandRec(normalized, pos + 1, remainingBudget - extra, sb, variants);
            // 回溯：撤销本位追加恢复现场，换下一档位重试
            sb.setLength(sb.length() - repeat);
            if (overflow) {
                // 任一子分支超封顶即整棵短路，无需继续枚举剩余档位
                return true;
            }
        }
        return false;
    }

    /**
     * 扫描文本（引擎对外唯一入口）：归一化 → ACDAT 全量匹配 → allow 区间裁决 →
     * deny 最长优先去重 → 规范词回映
     *
     * @param text 原始待扫文本（归一化在引擎内部完成，调用方传原文即可）
     * @return 命中的词库规范词列表（去重保序；空文本/无命中返回空列表，绝不 null）
     */
    @Override
    public List<String> scan(String text) {
        if (text == null || text.isEmpty() || denyTrie == null) {
            // 空文本/空黑名单：直接无命中（调用方 DfaFacade 已有空文本短路，此处兜底）
            return List.of();
        }
        // Step1 文本侧归一化（无损宽化，永不折叠重复——契约红线）
        String normalized = CharNormalizer.normalize(text);
        // Step2 deny 全量匹配：返回文本上全部命中区间（含嵌套/重叠，未启用 onlyWholeWords 类过滤）
        List<Hit<String>> denyHits = denyTrie.parseText(normalized);
        if (denyHits.isEmpty()) {
            return List.of();
        }
        // Step3 allow 全量匹配（有 deny 命中才需要裁决；空白名单按无 allow 命中处理）
        List<Hit<String>> allowHits = allowTrie == null
                ? List.of()
                : allowTrie.parseText(normalized);
        // Step4 allow 区间裁决（「完全落入才剔除」红线）：deny 命中区间完全落在任一 allow 命中
        // 区间内（含等界）→ 剔除；区间几何 = 左闭右开 [begin, end)，与旧引擎 [start, end) 同约定
        //（hankcs Hit 端点开闭口径在批次2 内核边界核对①专项复核）
        List<Hit<String>> survived = denyHits.stream()
                .filter(d -> allowHits.stream().noneMatch(a -> a.begin <= d.begin && d.end <= a.end))
                .toList();
        if (survived.isEmpty()) {
            return List.of();
        }
        // Step5 deny 最长优先去重：起点升序、同起点长命中在前；被更长 deny 完全覆盖的短命中剔除
        //（AB/B 同库 text=AB 只报 AB；部分重叠非包含的两个命中都保留，都算真实证据）
        List<Hit<String>> sorted = new ArrayList<>(survived);
        sorted.sort((x, y) -> x.begin != y.begin
                ? Integer.compare(x.begin, y.begin)
                : Integer.compare(y.end - y.begin, x.end - x.begin));
        // kept：最长优先去重后的幸存命中（保持起点有序，后续只增不被覆盖）
        List<Hit<String>> kept = new ArrayList<>(sorted.size());
        for (Hit<String> candidate : sorted) {
            // 已被 kept 里某个更长命中完全覆盖 → 剔除（长命中先入 kept，短命中才会被判覆盖）
            boolean covered = kept.stream().anyMatch(k -> k.begin <= candidate.begin && candidate.end <= k.end);
            if (!covered) {
                kept.add(candidate);
            }
        }
        // Step6 规范词回映：Hit.value 即构建期写入的词库规范词（原始业务词）；
        // LinkedHashSet 同一规范词多处/多变体命中只报一次，保持首次命中顺序
        Set<String> canonicalHits = new LinkedHashSet<>();
        for (Hit<String> hit : kept) {
            canonicalHits.add(hit.value);
        }
        return List.copyOf(canonicalHits);
    }
}
