package com.litisle.litisleapi.dfa;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AC 自动机扫描引擎（批次2 ③：TextScanEngine 的自研实现，替代 houbb SensitiveWordBs）
 *
 * <p>内核 = Aho-Corasick 多模式串自动机（trie + fail 失配指针 + 构建期聚合输出链），
 * 扫描复杂度 O(文本长度 + 命中数)，状态转移与词库规模无关——同步闸门「本地毫秒级」硬约束的根基。
 * 自研而非引库：变体展开/规范词回映/allow 区间裁决都是引擎语义的一部分，第三方裸 AC 库
 * 只覆盖 goto/fail 一小块；自研后同步闸门零新增第三方依赖，批次3 影子比对口径完全可控</p>
 *
 * <p>引擎语义契约（归一化分治/变体只长不短/pattern 同归一/命中裁决/规范词留痕）
 * 见 {@link TextScanEngine} 类注释，本类为实现方</p>
 *
 * <p>线程安全：build 产物全只读（数组 + 不可变结构），scan 可并发调用</p>
 *
 * <p>【完整逻辑·两条流水线】</p>
 * <ul>
 *   <li>构建期 {@link #build}（词库变更时执行一次）：deny/allow 词 → CharNormalizer 归一化
 *       （繁体/全角等无损宽化，词库侧与文本侧同口径）→ deny 词回溯展开重复变体（只长不短，
 *       单字符/总预算/变体数三重封顶）→ LinkedHashSet 同形去重（同形 pattern 只建一条 trie 路径）→
 *       逐 pattern 插入 trie → BFS 建 fail 失配指针并聚合输出链 → 三产物打包为全只读引擎实例</li>
 *   <li>扫描期 {@link #scan}（每次送审内容调用）：文本归一化 → AC 自动机 O(文本长度+命中数)
 *       全量匹配产出命中区间 → allow 区间裁决（deny 命中完全落入某 allow 区间才剔除）→
 *       deny 最长优先去重（被更长 deny 完全覆盖的短命中剔除）→ pattern 回映词库规范词 →
 *       返回去重保序的规范词列表</li>
 * </ul>
 */
@Slf4j
public final class AcTextScanEngine implements TextScanEngine {

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
     * （宁漏不长尾拖垮 trie；退化边界=每位置至多 ×3、全词总插入重复≤3 覆盖不到的极端拆写，
     * 由异步机审 LLM 层兜底）
     */
    private static final int MAX_VARIANTS_PER_WORD = 128;

    /** trie 注册的全部 pattern（已归一化；含 deny 变体与 allow 原形） */
    private final String[] patterns;

    /** pattern → 词库规范词 规范词回映表 （deny 变体回映用；allow pattern 对应槽位为 null）
     *
     * canonicalWords 是 pattern → 原始业务词的映射表，同时兼做 allow/deny 的身份标记（null=放行）。扫描命中后通过 patternIdx 查它，完成裁决和回映。
     */
    private final String[] canonicalWords;

    /**
     *  AC 自动机内核（pattern 索引与本数组对齐）
     * automaton 是 AC 自动机的完整内核——trie + fail 指针 + 聚合输出链，构建期一次性建好，扫描期只读，负责在归一化文本上做 O(n) 多模式匹配，产出所有命中的 pattern 索引和区间。
     */
    private final Automaton automaton;

    /**
     * 私有构造：仅由 {@link #build} 工厂调用，把构建期三产物打包成不可变引擎实例
     *
     * @param patterns       注册进自动机的全部 pattern（已归一化+变体展开，含 deny 变体与 allow 原形）
     * @param canonicalWords pattern → 词库规范词回映表（下标与 patterns 对齐；allow 槽位为 null）
     * @param automaton      AC 自动机内核（其 pattern 索引与 patterns 数组下标对齐）
     */
    private AcTextScanEngine(String[] patterns, String[] canonicalWords, Automaton automaton) {
        this.patterns = patterns;
        this.canonicalWords = canonicalWords;
        this.automaton = automaton;
    }

    /**
     * 构建引擎：归一化 → deny 变体展开（有界） → pattern 去重 → AC 自动机构建
     *
     * @param denyWords 黑名单词（敏感词）
     * @param allowWords 白名单词（放行词）
     * @return 可并发扫描的引擎实例
     */
    static TextScanEngine build(List<String> denyWords, List<String> allowWords) {
        // pattern 去重集：LinkedHashSet 保持注册顺序（同形词只建一个 trie 路径，级别映射由规范词侧保证）
//        pattern = 最终注册进 AC 自动机 trie 里的「标准形态字符串」
//       它是归一化 + 变体展开后的产物，是自动机真正用来匹配的东西。

        //pattern来源链路：
//        用户词库里的原始词（"好好學習"）
//  ↓  CharNormalizer.normalize()
//        归一化文本（"好好学习"）
//  ↓  expandRepeatVariants()
//        变体展开（"好好学习"、"好好好学习"、"好好学学习"…）
//  ↓  seen 去重后
//        pattern → 注册进 trie ✅

//        已注册的 pattern 字符串本身
        List<String> patternList = new ArrayList<>();
//        已注册的 pattern 字符串（与 seen 内容一致）
        List<String> canonicalList = new ArrayList<>();
//        每个 pattern 对应的 词库规范词（deny→原词，allow→null）
        Set<String> seen = new LinkedHashSet<>();

        // Step1 deny 词：归一化 → 变体展开（只长不短、有界） → 逐 pattern 注册并映射回规范词
        for (String word : denyWords) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            // 红线：词库侧 pattern 先过归一化（漏掉则繁体/全角词以原始形式进 trie，变形对抗静默失效）
                //文本归一化
            String normalized = CharNormalizer.normalize(word);

             // 把 deny 黑名单词加工成 pattern 并注册进 trie 的核心循环。我按执行顺序一步一步讲清楚它在干什么。
            // 逐变体注册：seen.add=false（同形 pattern 已注册过）则丢弃，防同形词重复建 trie 路径
            for (String variant : expandRepeatVariants(normalized, word)) {
                if (seen.add(variant)) {
                    // 收录 pattern，并登记「命中此 pattern 时回映到的词库规范词」（即当前原始词）
                    patternList.add(variant);
                    canonicalList.add(word);
                }
            }
        }
        // Step2 allow 词：仅归一化原形注册（不展开变体——放行词从严，变体形态不享受放行）
        for (String word : allowWords) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            String normalized = CharNormalizer.normalize(word);
            //这个 pattern 我之前有没有见过？没见过才注册，见过就直接丢掉
            if (seen.add(normalized)) {
                //已注册的 pattern 字符串本身
                patternList.add(normalized);
                //已注册的 pattern 字符串（与 seen 内容一致）
                canonicalList.add(null); //是用 null 当"标记位"，告诉扫描期：这个 pattern 是 allow（放行）词，不是 deny（命中）词。
            }
        }
        // Step3 AC 自动机构建（fail 链 + 输出聚合）
//        这三行是 build 的"最后一公里"：把收集好的 pattern 和映射表固化成数组，
//        喂给 AC 自动机建 trie+fail 链，然后把所有产物打包成一个不可变的 AcTextScanEngine 实例返回——从此引擎只读、可并发扫描。
        String[] patterns = patternList.toArray(String[]::new);
        String[] canonical = canonicalList.toArray(String[]::new);
        return new AcTextScanEngine(patterns, canonical, Automaton.build(patterns));
    }

    /**
     * deny 词重复变体展开（构建期，有损折叠的替代方案——只长不短）：
     * 回溯式预算展开——每字符额外重复 0..{@value MAX_EXTRA_REPEATS} 次（消耗预算），
     * 全词总插入重复 ≤ {@value MAX_TOTAL_EXTRA_REPEATS}，变体数 ≤ {@value MAX_VARIANTS_PER_WORD}；
     * 超封顶整词退化为仅原形并告警。必含原形（各位置选 0 额外重复的分支最先走）
     *
     * @param normalized 归一化后的词（变体基于归一形展开，保证与文本侧归一口径对齐）
     * @param origin     原始词（仅告警日志定位用）
     * @return 变体 pattern 列表（必含原形）
     */
    private static List<String> expandRepeatVariants(String normalized, String origin) {
        // LinkedHashSet：重复字符的词会出现不同组合向量生成同串（如 好好→(2,0)/(1,1) 同为 好好好好），需去重

        //variants = 当前这一个 deny 词展开出的所有变体 pattern 的临时收集桶
        Set<String> variants = new LinkedHashSet<>();
        //真正触发回溯展开，相当于按下了"开始生成所有变体"的按钮。
        boolean overflow = expandRec(normalized, 0, MAX_TOTAL_EXTRA_REPEATS,
                new StringBuilder(normalized.length() + MAX_TOTAL_EXTRA_REPEATS), variants);
        //返回值 overflow：true = 变体数量超了 MAX_VARIANTS_PER_WORD 封顶线。
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
     *
     * scan() 用"归一化→AC匹配→白名单区间剔除→黑名单最长去重→规范词回映"五步流水线，
     * 把用户输入文本转换成去重后的黑名单词列表；每一步都是对上一步产出的过滤/加工，最终只返回"真正违规且没被白名单兜住"的词。
     *
     * 给定 "好好学习" 这样的归一化词，用回溯枚举每个字符重复 1~3 次的所有组合，拼出 "好好学习"、"好好好学习"、"好好学学习" 等变体字符串，
     * 收集到 variants 里；一旦超过 128 个就紧急刹车返回 true，调用方退化为只保留原形。
     *
     * 参数	        递归中的角色
     * @param normalized	只读，原词 "好好学习"
     * @param pos	当前处理到第几个字符（0-based，逐步递增）
     * @param remainingBudget 	剩余"额外插入字符"的总预算（初始=3，逐层扣减）
     * @param sb	正在拼装的字符串草稿纸（递归中 append，返回时擦掉）
     * @param variants	收集桶（拼完一种组合就往里塞）
     */
    private static boolean expandRec(String normalized, int pos, int remainingBudget,
                                     StringBuilder sb, Set<String> variants) {

        /// expandRec 是一个带预算约束的回溯枚举器——从第一个字符开始，每个字符尝试重复 1~3 次（受全局预算限制），拼出一种完整组合就写入 variants，
        /// 超 128 个就短路返回 true 让上层退化为原形。它是整个变体对抗的"发动机"，用最少的代码把指数级组合空间压到可控范围内。

        // 终点：一种组合向量成形
        if (pos == normalized.length()) {
            if (variants.size() >= MAX_VARIANTS_PER_WORD) {
                // 变体数到达封顶：逐层短路返回 true，调用方整词退化为仅原形
                return true;
            }
            // 收录 StringBuilder 当前累积出的完整变体串
            variants.add(sb.toString());
            return false;
        }
        // 本位字符与其最大额外重复次数（受单字符上限与剩余总预算的双重约束）
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
     * 扫描文本（引擎对外唯一入口）：归一化 → AC 全量匹配 → allow/deny 区间裁决 →
     * deny 最长优先去重 → 变体回映规范词
     *
     * 原始文本 → 归一化 → AC 匹配 → allow/deny 裁决 → 最长优先去重 → 回映规范词 → 返回
     *给定一段用户输入文本，判断里面有没有命中黑名单词；如果有，返回命中的原始词库词列表（去重、有序）。白名单词覆盖到的位置不算命中。
     *
     * @param text 原始待扫文本（归一化在引擎内部完成，调用方传原文即可）
     * @return 命中的词库规范词列表（去重保序；空文本/无命中返回空列表）
     */
    @Override
    public List<String> scan(String text) {
        if (text == null || text.isEmpty() || patterns.length == 0) {
            // 空文本/空词库：直接无命中（调用方 DfaFacade 已有空文本短路，此处兜底）
            return List.of();
        }
        // Step1 文本侧归一化（无损宽化，永不折叠重复——契约红线）
        String normalized = CharNormalizer.normalize(text);
        // Step2 AC 全量匹配：产出全部子串命中（区间形式）
        List<Hit> hits = automaton.find(normalized);
        if (hits.isEmpty()) {
            return List.of();
        }

        // Step3 命中裁决（先 allow 后 deny，规则见 TextScanEngine 契约）：
        // 3.1 allow 命中优先——deny 命中完全落在任一 allow 命中区间内（含等界）→ 剔除
        // 按 pattern 的 allow/deny 身份分桶（canonicalWords 槽位为 null 即 allow 放行词）

        // allow/deny 区间裁决

//        实现逻辑：通过 canonicalWords[patternIdx] 是否为 null 判断身份——
//        null → 这个命中来自白名单词 → 放进 allowHits
//        非 null → 来自黑名单词 → 放进 denyHits
//        然后过滤：
        List<Hit> denyHits = new ArrayList<>();
        List<Hit> allowHits = new ArrayList<>();
        for (Hit hit : hits) {
            if (canonicalWords[hit.patternIdx()] == null) {
                allowHits.add(hit);
            } else {
                denyHits.add(hit);
            }
        }
        if (denyHits.isEmpty()) {
            return List.of();
        }

        //从 deny 命中里，过滤掉那些"完全被某个 allow 命中区间包住"的命中，只保留没被放行的。
//        实现逻辑：对每一个 deny 命中 d，检查是否存在任何一个 allow 命中 a 使得 d 的区间完全落在​ a 的区间内。
        //设计意图：白名单是"免死金牌"——用户文本里如果白名单词覆盖了黑名单词的位置，说明是正常用法，不算违规。
        List<Hit> survived = denyHits.stream()
                .filter(d -> allowHits.stream().noneMatch(a -> a.start() <= d.start() && d.end() <= a.end()))
                .toList();
        if (survived.isEmpty()) {
            return List.of();
        }

        // 3.2 deny 最长优先去重——命中区间被更长 deny 命中完全覆盖 → 剔除短命中
        //     （AB/B 同库 text=AB 只报 AB；部分重叠非包含的两个命中都保留，都算真实证据）
        //实现逻辑：排序规则——
            //起点升序（先出现的在前）
            //同起点时长度降序（长的在前）

        List<Hit> sorted = new ArrayList<>(survived);
        sorted.sort((x, y) -> x.start() != y.start()
                ? Integer.compare(x.start(), y.start())
                : Integer.compare(y.length(), x.length()));
        // kept：最长优先去重后的幸存命中（保持起点有序，后续只增不被覆盖）
        List<Hit> kept = new ArrayList<>(sorted.size());

            //实现逻辑：对排序后的每个候选命中，检查是否已被 kept 里已有的某个命中完全覆盖。
            //因为长的在前，kept 里先存的是长命中
            //后到的短命中如果落在已有长命中的区间里 → covered=true → 剔除
        for (Hit candidate : sorted) {
            boolean covered = kept.stream().anyMatch(k ->
                    k.start() <= candidate.start() && candidate.end() <= k.end());
            if (!covered) {
                kept.add(candidate);
            }
        }

        // Step4 变体回映规范词（选型 A：hitWords=词库规范词，审计证据=命中哪个黑名单词）
        // LinkedHashSet：同一规范词多处/多变体命中只报一次，且保持首次命中顺序
            //实现逻辑：
            //遍历存活的 deny 命中，通过 patternIdx 查 canonicalWords 拿到原始词库词
            //LinkedHashSet 保证：同一规范词多次命中只出现一次，且保持首次出现顺序
            //返回 List<String> 给调用方
         //为什么不直接返回 pattern：pattern 是 "好好好学习" 这种展开形态，调用方需要的是 "好好學習" 这种业务语义上的原始词。
        Set<String> canonicalHits = new LinkedHashSet<>();
        for (Hit hit : kept) {
            canonicalHits.add(canonicalWords[hit.patternIdx()]);
        }
        return List.copyOf(canonicalHits);
    }

    /**
     * 命中区间的不可变记录：patternIdx 对齐引擎 patterns 数组，[start, end) 为归一化文本上的区间
     */
    private record Hit(int patternIdx, int start, int end) {
        int length() {
            return end - start;
        }
    }

    /**
     * Aho-Corasick 自动机内核（构建期一次性建好，扫描期只读）：
     * trie 前缀树 + BFS 失配指针 + 输出链聚合（fail 链上的命中在建图时合并进节点，
     * 扫描期零回溯，保证 O(文本长度 + 命中数)）
     */
    private static final class Automaton {

        /** 自动机节点：子状态转移表（稀疏）+ 失配指针 + 本节点命中（含 fail 链聚合）的 pattern 索引 */
        private static final class Node {
            /** 子字符 → 子节点编号（稀疏表：中文词库下全表展开反而浪费内存） */
            final Map<Character, Integer> next = new HashMap<>(4);
            /** 失配指针（最长真后缀节点；根为自身） */
            int fail;
            /** 本节点命中的 pattern 索引（建图时已聚合 fail 链输出，扫描期直接遍历） */
            final List<Integer> output = new ArrayList<>(2);
        }

        /** 节点池（0 号 = 根） */
        private final List<Node> nodes = new ArrayList<>();
        /** pattern 长度缓存（扫描期把状态命中换算成文本区间用） */
        private final int[] patternLengths;

        /**
         * 私有构造：登记 pattern 长度表并占位根节点（0 号），节点池由 build 逐步填充
         *
         * @param patternLengths 各 pattern 长度（下标对齐 patterns 数组，扫描期按长度倒推命中起点）
         */
        private Automaton(int[] patternLengths) {
            this.patternLengths = patternLengths;
            // 根节点占位
            nodes.add(new Node());
        }

        /**
         * 由 pattern 数组建自动机：插入 trie → BFS 建 fail 链 → 输出聚合
         *
         * @param patterns 全部待注册 pattern（已归一化+变体展开；下标与引擎 patterns 数组对齐）
         * @return 构建完成的只读自动机（扫描期可并发调用）
         */
        static Automaton build(String[] patterns) {
            // 长度表与 pattern 一同登记（扫描期命中时按长度倒推文本区间）
            Automaton automaton = new Automaton(Arrays.stream(patterns).mapToInt(String::length).toArray());
            // Step1 逐 pattern 插入 trie
            for (int p = 0; p < patterns.length; p++) {
                // 从根出发沿字符逐层落点，公共前缀自动共享路径
                int state = 0;
                for (char c : patterns[p].toCharArray()) {
                    state = automaton.nextOrCreate(state, c);
                }
                // 终点节点登记本 pattern 索引（fail 链上的后缀命中在 Step2 聚合进来）
                automaton.nodes.get(state).output.add(p);
            }
            // Step2 BFS 建 fail 指针 + 输出聚合（层级序保证处理子节点时父层 fail 已就绪）
            Deque<Integer> queue = new ArrayDeque<>();
            for (Integer child : automaton.nodes.get(0).next.values()) {
                // 根的直接子节点 fail=根；入队
                automaton.nodes.get(child).fail = 0;
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                // BFS 层序出队：保证处理子节点时其 fail 目标（更浅层节点）已处理完毕
                int current = queue.poll();
                Node node = automaton.nodes.get(current);
                // 输出聚合：本节点命中 = 自身 pattern + fail 链上后缀命中（fail 节点输出已聚合，直接合并）
                node.output.addAll(automaton.nodes.get(node.fail).output);
                for (Map.Entry<Character, Integer> edge : node.next.entrySet()) {
                    char c = edge.getKey();
                    int child = edge.getValue();
                    // 沿 fail 链找 c 的转移，找不到一路退回根
                    int f = node.fail;
                    while (f != 0 && !automaton.nodes.get(f).next.containsKey(c)) {
                        f = automaton.nodes.get(f).fail;
                    }
                    Integer fallback = automaton.nodes.get(f).next.get(c);
                    // fail 不能指向自身（同字符自环）
                    int fail = (fallback == null || fallback == child) ? 0 : fallback;
                    automaton.nodes.get(child).fail = fail;
                    queue.add(child);
                }
            }
            return automaton;
        }

        /**
         * 在归一化文本上全量扫描，返回全部命中区间（同 pattern 多位置、跨 pattern 重叠都保留，
         * 裁决由引擎 scan 层负责——内核只忠实上报）
         *
         *
         * @param normalized 归一化后的待扫文本（必须与词库侧同口径归一，否则变形对抗静默失效）
         * @return 全部命中区间列表（无命中返回空列表）
         */
        List<Hit> find(String normalized) {
            // 命中收集器（区间形式：同 pattern 多位置、跨 pattern 重叠都保留，裁决交给引擎 scan 层）
            List<Hit> hits = new ArrayList<>();
            // 从根节点（0 号）出发逐字符推进状态
            int state = 0;
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                // 失配沿 fail 链回退到有 c 转移的节点（或根）
                Node node = nodes.get(state);
                while (state != 0 && !node.next.containsKey(c)) {
                    state = node.fail;
                    node = nodes.get(state);
                }
                // 落到下一状态；彻底无转移则回根（本轮匹配从头开始）
                Integer next = node.next.get(c);
                state = next == null ? 0 : next;
                // 当前状态聚合输出 = 文本以 i 结尾的全部命中 pattern
                for (int p : nodes.get(state).output) {
                    // 按命中 pattern 长度倒推区间起点，产出 [start, end) 左闭右开区间
                    hits.add(new Hit(p, i - patternLengths[p] + 1, i + 1));
                }
            }
            return hits;
        }

        /**
         * 取/建子节点编号（trie 插入专用）
         *
         * @param state 当前节点编号
         * @param c     转移字符
         * @return c 对应的子节点编号（已存在则复用，不存在则新建）
         */
        private int nextOrCreate(int state, char c) {
            Node node = nodes.get(state);
            Integer existing = node.next.get(c);
            if (existing != null) {
                // 已有该字符转移：直接复用（公共前缀共享同一条 trie 路径）
                return existing;
            }
            // 新建节点：编号取当前节点池大小（追加到池尾），并登记进父节点转移表
            int created = nodes.size();
            node.next.put(c, created);
            nodes.add(new Node());
            return created;
        }
    }
}
