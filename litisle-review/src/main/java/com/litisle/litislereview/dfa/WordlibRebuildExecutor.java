package com.litisle.litislereview.dfa;

import com.litisle.litisleapi.dfa.DfaFacade;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 词库引擎热更构建执行器（批次2 ④：rebuild 异步化，仅热更路径）
 *
 * <p>【为什么异步】rebuild 需全量装载词表 + 变体展开 + 建 AC 自动机，大词库秒级耗时；
 * 原 WordlibChangeSubscriber 在 Pub/Sub 订阅线程同步调 rebuild，会阻塞订阅线程、
 * 拖慢同频道的其他广播。改由本执行器独立单线程池执行，订阅回调仅投递任务即刻返回</p>
 *
 * <p>【红线：仅限热更路径】启动初始化（initAll）必须保持同步调 rebuild——
 * 装载失败中断启动（fail-fast）；整体异步化=带病启动（引擎未就绪，首个请求才炸）。
 * 本执行器只接订阅广播触发的热更，启动路径禁止使用</p>
 *
 * <p>【串行 + 去重】单线程池保证跨场景串行（并发 rebuild 会竞态 engines.put 致旧版本
 * 后写回退）；同场景去重：场景已在队列则本次广播直接丢弃——rebuild 构建时才读 loader
 * 最新版本，连续广播天然收敛到最新版；若构建期间又有新广播（场景标记已在任务开始时摘除）
 * 会再次入队补一轮构建，保证最终收敛到最新词库</p>
 *
 * <p>【失败兜底】热更失败仅记 error + 失败指标，不向上抛（Pub 无重投，抛出只会打断订阅线程）；
 * rebuild 失败发生在引擎原子替换之前 → 旧引擎原样保留，实例继续用旧词库直至下次广播收敛</p>
 */
@Slf4j
@Component
public class WordlibRebuildExecutor {

    /** 热更构建单线程池（跨场景串行防 engines.put 竞态；daemon 不阻塞 JVM 退出） */
    private final ExecutorService executor;

    /** 已排队待构建的场景集合（同场景去重；任务开始时摘除，构建中再来的广播可重新入队） */
    private final Set<String> queuedScenes = ConcurrentHashMap.newKeySet();

    /** DFA 门面（真正执行 rebuild 的目标） */
    private final DfaFacade dfaFacade;

    /** 词库观测埋点（rebuild 成功/失败计数，optional 模式） */
    private final WordlibMetrics wordlibMetrics;

    public WordlibRebuildExecutor(DfaFacade dfaFacade, WordlibMetrics wordlibMetrics) {
        this.dfaFacade = dfaFacade;
        this.wordlibMetrics = wordlibMetrics;
        // 单线程 + daemon + 具名线程（排查线程 dump 时可识别），跨场景严格串行
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "wordlib-rebuild-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * 提交单场景热更构建任务（同场景去重：已在队列的直接丢弃，收敛到最新版）
     *
     * @param scene 词库场景（DfaScenes 文本场景 code）
     */
    public void submit(String scene) {
        // 去重：场景已排队则跳过（当前排队的任务构建时会读 loader 最新版本，无需重复排队）
        if (!queuedScenes.add(scene)) {
            log.info("词库热更去重：scene={} 已在构建队列，跳过本次广播（构建时读最新版本）", scene);
            return;
        }
        executor.execute(() -> {
            // 先摘场景标记再构建：构建期间的新广播可重新入队，保证收敛到最新词库
            queuedScenes.remove(scene);
            try {
                // rebuild 内部：version 乐观比对（旧版本直接跳过）→ 装载 → 建 AC 引擎 → 原子替换
                dfaFacade.rebuild(scene);
                wordlibMetrics.incrementRebuild(scene, true);
            } catch (Exception e) {
                // 失败兜底：旧引擎保留（rebuild 失败不会走到引擎替换），实例沿用旧词库直至下次广播
                wordlibMetrics.incrementRebuild(scene, false);
                log.error("词库热更构建失败（保留旧引擎沿用旧词库）：scene={}", scene, e);
            }
        });
    }

    /**
     * 停机时优雅关闭构建线程池（等待在途构建完成，不接收新任务）
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
