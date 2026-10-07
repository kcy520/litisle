package com.litisle.litisleapi.dfa;



import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 把"词库"变成"能用的引擎"，然后用引擎去检查文本里有没有敏感词，决定拦不拦。
 * DFA 同步闸门门面（每场景独立引擎，热更原子换引用，发布链路本地执行零网络）
 *
 * 阶段	方法	        干什么
 * 启动	initAll()	按场景逐个构建 DFA 引擎，加载失败直接让应用起不来（宁严勿松）
 * 热更	rebuild()	词库变了 → 加载新词 → 建新引擎 → 原子替换旧引擎，线上请求不受影响
 * 查词	check()	    拿到文本 → 归一场景 → DFA 匹配 → 返回命中词 + 最高敏感级别
 *
 * <p>【使用场景示例】宿主服务启动装配（SmartInitializingSingleton）：</p>
 * <pre>{@code
 * dfaFacade.initAll();                       // 启动逐场景构建引擎（fail-fast：加载失败抛异常）
 * DfaHitResult r = dfaFacade.check("ARTICLE_FEATURED", title + content);
 * if (r.hit() && r.maxLevel() == 1) throw new BizException(70001);   // HIGH 同步拦截
 * // MID 命中不拦截，把 r.hitWords()/r.wordlibVer() 随内容送审留痕
 * }</pre>
 * <p>热更（Redis Pub 订阅端）：收到广播后调 {@link #rebuild}，内部 version 乐观比对
 * （新版本号 ≤ 当前版本则忽略，防乱序旧覆盖新）+ 原子换引擎，在途查询不受影响</p>
 *
 * <p>【词库源纪律】houbb 仅提供算法与变体识别（繁简/拼音/跳词/全半角），词库完全来自
 * {@link WordLibraryLoader}（不链 WordDenys.defaults()，避免与内置双词库源不一致，§4.4.4）</p>
 *
 * <p>【宁严勿松】引擎未初始化时 {@link #check} 抛 IllegalStateException，绝不按无命中放行；
 * 命中词未在级别映射中（理论不可达）按 HIGH 兜底</p>
 */
@Slf4j
public class DfaFacade {

    /** 词库加载器，负责读取词表和版本号 */
    private final WordLibraryLoader loader;
    /** 场景 → 引擎快照（版本 + houbb引擎 + 词级别映射） */
    private final ConcurrentHashMap<String, Engine> engines = new ConcurrentHashMap<>();

    /**
     * 不可变引擎快照：版本 + 构建好的 houbb 引擎 + 词→级别映射（命中后分类用）
     */
    private record Engine(long version, SensitiveWordBs bs, Map<String, Integer> wordLevels) {
    }

    public DfaFacade(WordLibraryLoader loader) {
        // 引擎数据源唯一入口：词表/版本全部经 WordLibraryLoader SPI 装载（宿主各自实现）
        this.loader = loader;
    }

    /**
     * 启动初始化：逐文本场景构建引擎；任何场景加载失败直接抛异常中断启动（fail-fast）
     */
    public void initAll() {
        // 逐文本场景串行构建；rebuild 内部 fail-fast，任一场景词库装载失败即中断启动（宁严勿松）
        for (String scene : DfaScenes.textScenes()) {
            rebuild(scene);
        }
    }

    /**
     * 构建/重建单场景引擎（热更入口）：version 乐观比对防乱序，原子换引用不中断在途查询
     * 引擎 = 一份已经构建好的敏感词 DFA 匹配器 + 它的词库版本 + 每个词对应的敏感级别映射
     */
    public synchronized void rebuild(String scene) {
        // 加载词库版本号（用于后续乐观锁比对）
        long version = loader.loadVersion(scene);
        // 加载当前场景全量词条目
        List<WordEntry> entries = loader.loadWords(scene);
        // 提取黑名单词（敏感词）
        List<String> denyWords = entries.stream().filter(WordEntry::blacklist).map(WordEntry::word).toList();
        // 提取白名单词（放行词，优先级高于黑名单）
        List<String> allowWords = entries.stream().filter(e -> !e.blacklist()).map(WordEntry::word).toList();
        // 构建词→敏感级别映射，重复词取更严级别（Math.min）
        Map<String, Integer> wordLevels = entries.stream().filter(WordEntry::blacklist)
                .collect(Collectors.toMap(WordEntry::word, WordEntry::level, Math::min));
        // 构建 houbb DFA 引擎，注入黑名单和白名单
        SensitiveWordBs bs = SensitiveWordBs.newInstance()
                .wordDeny(() -> denyWords)
                .wordAllow(() -> allowWords)
                .init();
        // 获取当前已存在的引擎
            //  如果是第一次构建（initAll 阶段），current 是 null
            //  如果是热更阶段，current 指向旧引擎
        Engine current = engines.get(scene);
        // 版本号不递增则忽略（防 Redis 广播乱序覆盖新版本）   版本号比对，防止旧广播覆盖新版本s
        if (current != null && version <= current.version()) {
            log.warn("词库热更忽略旧版本：scene={}, 广播 version={} ≤ 当前 version={}（乱序防护）",
                    scene, version, current.version());
            //return 了，是用原有引擎
            //初始化阶段 current 是 null，根本走不进这个 if,也就不会return
            return;
        }
        // 原子替换引擎引用，旧引擎在途查询不受影响
        engines.put(scene, new Engine(version, bs, wordLevels));
        log.info("DFA 引擎构建完成：scene={}, version={}, 黑名单词={}, 白名单词={}",
                scene, version, denyWords.size(), allowWords.size());
    }

    /**
     * 同步闸门判定：HIGH→拦截 70001，MID→只标记；未初始化/非文本场景 fail-fast
     */
    public DfaHitResult check(String scene, String text) {
        // 场景归一化，非文本场景返回 null 直接拒绝
        String normalized = DfaScenes.normalize(scene);
        if (normalized == null) {
            throw new IllegalArgumentException("非文本场景不走 DFA 闸门：" + scene
                    + "（IMAGE/ARTICLE_IMG_RE_REVIEW 归一为 null，检查调用方场景传参）");
        }
        // 空文本快速返回未命中
        if (text == null || text.isEmpty()) {
            return DfaHitResult.noHit(currentVersion(normalized));
        }
        // 获取当前场景引擎，未初始化则抛异常（宁严勿松，禁止放行）
        Engine engine = engines.get(normalized);
        if (engine == null) {
            throw new IllegalStateException("DFA 引擎未初始化：scene=" + normalized
                    + "（未 initAll / 词库加载失败被跳过），禁止 default-allow 放行");
        }
        // DFA 全量匹配，去重
            //这行代码 = 拿当前场景的 DFA 引擎对文本做全量敏感词匹配，把结果去重后变成一份干净的命中词列表，供后续判断级别和返回给调用方。
        /// houbb 自带 DFA 审核算法 + 变体识别能力，但词库、级别分类、拦截策略全是你的。它只回答"文本里有没有词库里的词"，不回答"该不该拦截"。
        List<String> hits = engine.bs().findAll(text).stream().distinct().toList();
        // 未命中直接返回
        if (hits.isEmpty()) {
            return DfaHitResult.noHit(engine.version());
        }
        // 计算最高敏感级别：取命中最严级别（level 值最小），未配置兜底为 HIGH(1)
        int maxLevel = hits.stream()
                .mapToInt(w -> engine.wordLevels().getOrDefault(w, 1))
                .min()
                .orElse(1);
        // 返回命中结果（是否命中 + 最高级别 + 命中词列表 + 词库版本）
        return new DfaHitResult(true, maxLevel, hits, engine.version());
    }

    /**
     * 当前生效版本（观测/留痕兜底；场景未初始化返回 -1）
     */
    public long currentVersion(String scene) {
        // 场景未构建（未 initAll/被跳过）返回 -1 占位，调用方按未知态保守处理
        Engine engine = engines.get(scene);
        return engine == null ? -1L : engine.version();
    }

}