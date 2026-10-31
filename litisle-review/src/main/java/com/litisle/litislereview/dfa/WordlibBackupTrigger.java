package com.litisle.litislereview.dfa;

import com.litisle.litisleapi.dfa.WordEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 词库备份异步触发器（批次1 ①）：挂在 WordlibVersionService 变更事务 afterCommit 之后
 *
 * <p>【异步语义】备份落盘是磁盘 IO，丢进独立单线程后台执行，绝不拖慢词库变更主链路；
 * 同一线程串行化执行，天然避免并发写同一 scene 备份目录。</p>
 *
 * <p>【失败语义】备份失败仅记 error 告警，不重抛不影响词库变更（词表事务已提交）；
 * 备份缺口由下次变更或人工补备兜底。</p>
 *
 * <p>【未启用语义】备份未激活（backup.enabled≠true 时无 WordlibBackupStore 实现 Bean）
 * getIfAvailable 返回 null，直接跳过，词库变更链路零影响。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WordlibBackupTrigger {

    /** 备份存储实现（可选依赖：未激活备份时容器内无实现 Bean） */
    private final ObjectProvider<WordlibBackupStore> storeProvider;

    /**
     * 备份专用单线程池（daemon 线程不阻碍 JVM 退出；串行执行防并发写同一 scene 目录）
     */
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wordlib-backup");
        t.setDaemon(true);
        return t;
    });

    /**
     * 事务提交后异步备份（fire-and-forget）：调用方（afterCommit 回调）立即返回，落盘在后台线程执行
     *
     * @param scene   词库场景码
     * @param version 本次变更推进后的新版本号
     * @param words   该版本全量启用词条（调用方保证非空）
     */
    public void backupAfterCommit(String scene, long version, List<WordEntry> words) {
        // 备份未启用（无实现 Bean）：直接跳过，零开销
        WordlibBackupStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        backupExecutor.execute(() -> {
            try {
                store.save(scene, version, words);
            } catch (Exception e) {
                // 备份失败仅告警不阻断变更：词表变更已提交，备份缺口由下次变更/人工补备兜底
                log.error("词库冷备失败（仅告警，不影响词库变更）：scene={}, version={}", scene, version, e);
            }
        });
    }

    /**
     * 应用停机时优雅关闭备份线程池（未执行的备份任务直接丢弃即可，可由下次变更补齐）
     */
    @PreDestroy
    public void shutdown() {
        backupExecutor.shutdownNow();
    }
}
