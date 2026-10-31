package com.litisle.litislereview.dfa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.WordEntry;
import com.litisle.litisleapi.dfa.WordLibraryLoader;
import com.litisle.litisleapi.dfa.WordlibChangedMessage;
import com.litisle.litisleapi.dfa.WordlibSnapshot;
import com.litisle.litislereview.domain.entity.SensitiveWordVersion;
import com.litisle.litislereview.domain.entity.WordlibChangeAudit;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.mapper.SensitiveWordVersionMapper;
import com.litisle.litislereview.mapper.WordlibChangeAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 词库版本推进 + 变更广播服务（板块二：sensitive_word_version 版本单一来源，§2.5）
 *WordlibVersionService = 词库变更的"收口服务"：任何地方改了敏感词表，都必须调它来推进版本号 + 通知所有实例"词库变了，该重建了"。
 *
 * <p>【推进语义】任何词表变更（增删改/启停/黑白切换/导入）收口调用
 * {@link #bumpAndBroadcast}：原子 update version=version+1（与调用方词表变更同事务，
 * REQUIRED 加入宿主事务保证原子性）；无版本行则首插 version=1，并发首插冲突重试一次 update</p>
 *
 * <p>【变更审计】版本推进同事务内插入 wordlib_change_audit（批次1 ②：红线=审计不同事务不可信，
 * 审计失败则整个变更一并回滚；单条逐词 / 批量聚合 1 条，backup_file 引用列指向冷备文件）</p>
 *
 * <p>【发布时机】TransactionSynchronization.afterCommit——事务提交成功才执行
 * 「写 Redis 词库快照 → Pub 广播」（先快照后广播，保证订阅端重建时读到新词库）；
 * 回滚不广播，杜绝假广播（与板块三「本地事务提交→发布」同一纪律）</p>
 *
 * <p>【冷备触发】快照/广播之外，afterCommit 内经 {@link WordlibBackupTrigger} 异步触发词库冷备
 * （批次1 ①：版本化 gz 文件落盘，备份失败仅告警不阻断变更；Redis 快照=热备，备份文件=冷备）</p>
 *
 * <p>【可靠性语义】afterCommit 内 Redis 失败仅记 error 不重抛（事务已提交）：广播丢失由
 * 下次变更或重启收敛，快照丢失由下次变更覆盖；Pub fire-and-forget 见 WordlibChangedMessage</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WordlibVersionService {

    private final SensitiveWordVersionMapper versionMapper;
    private final WordlibChangeAuditMapper auditMapper;
    private final WordLibraryLoader wordLibraryLoader;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    /** 词库备份异步触发器（批次1 ①：内部 ObjectProvider 可选依赖，备份未启用时零开销跳过） */
    private final WordlibBackupTrigger backupTrigger;

    /**
     * 版本 +1（同事务）+ 注册 afterCommit 回调（写快照 + Pub 广播）；
     * 必须在词表变更的事务内调用（本方法 REQUIRED 加入宿主事务）
     *
     * 词表变更后调用，一步搞定两件事：版本号 +1 + 注册"事务提交后广播"钩子。
     */
    @Transactional(rollbackFor = Exception.class)
    public void bumpAndBroadcast(WordSceneEnum scene, Long operatorId) {
        // 无审计上下文的旧入口：委托重载方法，审计列跳过不落库
        bumpAndBroadcast(scene, operatorId, null);
    }

    /**
     * 版本 +1 + 词库变更审计插入（同一事务）+ 注册 afterCommit 回调（写快照 + Pub 广播）；
     * 必须在词表变更的事务内调用（本方法 REQUIRED 加入宿主事务）
     *
     * @param audit 审计上下文（批次1 ②：单条变更逐词 / 批量导入聚合 1 条；null=不落审计）
     */
    @Transactional(rollbackFor = Exception.class)
    public void bumpAndBroadcast(WordSceneEnum scene, Long operatorId, WordlibAuditContext audit) {
        // Step1 版本原子 +1：REQUIRED 传播加入调用方词表变更事务（词表回滚则版本一并回滚）
        long newVersion = bumpInTx(scene, operatorId);
        // Step2 词库变更审计插入（红线：与词表变更同事务——审计失败=整个变更一并回滚，不可信的审计宁可不落）
        insertAuditInTx(scene, operatorId, newVersion, audit);
        // Step3 注册 afterCommit 钩子：事务提交成功后才执行「写redis快照+广播」，回滚不广播（杜绝假广播）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishSnapshotAndNotify(scene, newVersion, operatorId);
            }
        });
    }

    /**
     * 同事务插入词库变更审计行（wordlib_change_audit，批次1 ②）：
     * 单条变更逐词 1 行 / 批量导入聚合 1 行；audit 为 null（无审计上下文入口）跳过；
     * backup_file 引用列按统一口径生成（与备份 SPI 落盘文件一一对应，词全量在备份文件内不重复落库）
     */
    private void insertAuditInTx(WordSceneEnum scene, Long operatorId, long newVersion, WordlibAuditContext audit) {
        if (audit == null) {
            return;
        }
        auditMapper.insert(new WordlibChangeAudit()
                .setScene(scene)
                .setOpType(audit.opType())
                .setWord(audit.word())
                .setDetail(audit.detail())
                .setWordCount(audit.wordCount())
                .setBackupFile(WordlibBackupStore.backupFileName(scene.getCode(), newVersion))
                .setVersion(newVersion)
                .setOperatorId(operatorId)
                .setCreateTime(LocalDateTime.now()));
    }

    /**
     * 原子推进：update version=version+1 → affected=0 首插 version=1 → 首插并发冲突重试 update
     * 把 sensitive_word_version 表里该 scene 的版本号原子 +1；没有行就插入第一行。
     */
    private long bumpInTx(WordSceneEnum scene, Long operatorId) {
        // Step1 原子自增：version = version + 1 在 DB 侧执行，规避「读-改-写」并发丢更新
        long affected = versionMapper.update(null, new LambdaUpdateWrapper<SensitiveWordVersion>()
                .setSql("version = version + 1")
                .set(SensitiveWordVersion::getUpdatedBy, operatorId)
                .set(SensitiveWordVersion::getUpdateTime, LocalDateTime.now())
                .eq(SensitiveWordVersion::getScene, scene));
        // Step2 命中=0 说明该场景尚无版本行（首次变更）：首插 version=1
        if (affected > 0) {
            return selectVersion(scene);
        }
        try {
            versionMapper.insert(new SensitiveWordVersion()
                    .setScene(scene).setVersion(1L).setUpdatedBy(operatorId)
                    .setUpdateTime(LocalDateTime.now()));
            return 1L;
        } catch (DuplicateKeyException e) {
            // Step3 并发首插冲突（另一事务先插了）：重试一次原子自增拿真实新版本
            long retried = versionMapper.update(null, new LambdaUpdateWrapper<SensitiveWordVersion>()
                    .setSql("version = version + 1")
                    .set(SensitiveWordVersion::getUpdatedBy, operatorId)
                    .set(SensitiveWordVersion::getUpdateTime, LocalDateTime.now())
                    .eq(SensitiveWordVersion::getScene, scene));
            // 重试仍未命中属异常态（版本行被并发删除？），fail-fast 暴露问题
            if (retried == 0) {
                throw new IllegalStateException("词库版本推进失败（并发首插后 update 仍未命中）：scene=" + scene, e);
            }
            return selectVersion(scene);
        }
    }


    /**
     * 提交后动作：刷新 Redis 词库快照（先）→ Pub 广播（后）→ 触发词库冷备（异步，finally）；
     * 失败仅记日志
     *
     * 事务提交成功后执行：重新装载词库 → 写 Redis 快照 → Pub 广播。三步顺序不可颠倒；
     * 冷备与快照/广播解耦（finally 触发）：快照/广播失败不影响已提交词表的备份。
     */
    private void publishSnapshotAndNotify(WordSceneEnum scene, long newVersion, Long operatorId) {
        // 装载结果提到 try 外：finally 中冷备需要引用（装载失败为 null 时跳过备份）
        List<WordEntry> words = null;
        try {
            // Step1 重新装载全量词条 与 最新版本（查表路径同时刷新 review 本地文件快照，一举两得）
            words = wordLibraryLoader.loadWords(scene.getCode());
            long version = wordLibraryLoader.loadVersion(scene.getCode());
            // Step2 写 Redis 词库快照（先快照后广播：content/message 订阅端 rebuild 时读快照装载新词库）
                //objectMapper.writeValueAsString : 把 Java 对象序列化成 JSON 字符串
            String snapshotJson = objectMapper.writeValueAsString(new WordlibSnapshot(version, words));
            stringRedisTemplate.opsForValue().set(
                    String.format(ModerationConstant.WORDLIB_SNAPSHOT_KEY, scene.getCode()), snapshotJson);
            // Step3 发布广播（fire-and-forget：订阅端 rebuild 内 version 乐观比对防乱序）
            String msgJson = objectMapper.writeValueAsString(new WordlibChangedMessage(
                    scene.getCode(), version, operatorId, System.currentTimeMillis()));
            stringRedisTemplate.convertAndSend(ModerationConstant.WORDLIB_CHANGE_CHANNEL, msgJson);
            log.info("词库变更已广播：scene={}, version={}, 快照词数={}", scene, version, words.size());
        } catch (Exception e) {
            log.error("词库快照写入/广播失败（词表变更已提交，各实例沿用旧词库直至下次变更或重启）：scene={}, 目标 version={}",
                    scene, newVersion, e);
        } finally {
            // Step4 词库冷备（批次1 ①）：afterCommit 异步落盘，备份失败由触发器内部仅告警不阻断变更；
            // 装载失败（words=null）无备份对象，跳过由下次变更补齐
            if (words != null && !words.isEmpty()) {
                backupTrigger.backupAfterCommit(scene.getCode(), newVersion, words);
            }
        }
    }

    /**
     * 从mysql读当前版本（推进后取值用；无行返回 0）
     *
     * 推进版本后读一下返回最新值。纯查询，无副作用。
     */
    private long selectVersion(WordSceneEnum scene) {
        // 查 scene 主键行；无行/版本为 null 返回 0（仅在推进成功后取值，理论上不会走到 0）
        SensitiveWordVersion row = versionMapper.selectOne(
                new LambdaQueryWrapper<SensitiveWordVersion>()
                        .eq(SensitiveWordVersion::getScene, scene));
        return row == null || row.getVersion() == null ? 0L : row.getVersion();
    }

}