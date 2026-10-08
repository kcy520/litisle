package com.litisle.litislereview.dfa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.dfa.WordEntry;
import com.litisle.litisleapi.dfa.WordLibraryLoader;
import com.litisle.litislereview.domain.entity.SensitiveWordLibrary;
import com.litisle.litislereview.domain.entity.SensitiveWordVersion;
import com.litisle.litislereview.enums.WordListTypeEnum;
import com.litisle.litislereview.enums.WordSceneEnum;
import com.litisle.litislereview.enums.WordStatusEnum;
import com.litisle.litislereview.mapper.SensitiveWordLibraryMapper;
import com.litisle.litislereview.mapper.SensitiveWordVersionMapper;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * review 侧词库装载器（WordLibraryLoader 的查表实现，§4.4.4）
 *
 *
 * TableWordLibraryLoader 是 DFA 敏感词引擎的「词库数据供应器」——负责从数据库加载启用中的敏感词词条和版本号，
 * 并在内存/DB 不可用时，用本地 JSON 快照兜底，确保 DFA 闸门绝不空词库 default-allow。
 *它是 DFA 引擎和持久化存储之间的适配器（Loader），DFA 框架不关心数据从哪来，只认 WordLibraryLoader 接口；
 * 你这个类负责把"数据库里的词"变成"DFA 能吃的 WordEntry 列表"。
 *
 * <p>【选型速查】宿主=litisle-review：查 sensitive_word_library 表（scene + status=1，
 * 黑+白名单一并装载，DfaFacade 按 blacklist 分流入 houbb 词库）；版本取 sensitive_word_version
 * （scene 级 SSOT，行级 version 仅审计快照不在此消费）</p>
 *
 * <p>【兜底纪律（宁严勿松，§4.4.4）】查表失败或结果为空时，回退本地持久化快照（上次成功装载的
 * 非空词库）并告警，绝不静默空词库 default-allow；回退后仍无可用数据则抛异常——启动期由
 * SmartInitializingSingleton 中断启动，运行期由 DfaFacade.rebuild 抛出（旧引擎继续生效）</p>
 *
 * <p>【快照格式】JSON（词库快照禁 Java 原生反序列化纪律），目录外置
 * litisle.moderation.wordlib.snapshot-dir，按 scene 一文件（snapshot-{scene}.json），
 * 写入采用「临时文件 + 原子 move」防半途崩溃留截断快照</p>
 *
 * <p>【场景入参】仅接受 DfaScenes 归一后的四个文本场景（ARTICLE/COMMENT/USERNAME/MESSAGE），
 * 越界 scene 经 valueOf 抛 IllegalArgumentException 走无兜底分支快速暴露传参错误</p>
 */
@Slf4j
@Component
public class TableWordLibraryLoader implements WordLibraryLoader {




    /** 无版本记录/版本未知占位（与契约口径一致） */
    private static final long NO_VERSION = -1L;

    private final SensitiveWordLibraryMapper libraryMapper;
    private final SensitiveWordVersionMapper versionMapper;
    private final ModerationProperties properties;
    /** 快照读写专用 Mapper（副本）：关闭未知字段失败，保证快照文件向前兼容（新增字段不炸旧实例） */
    private final ObjectMapper snapshotMapper;

    public TableWordLibraryLoader(SensitiveWordLibraryMapper libraryMapper,
                                  SensitiveWordVersionMapper versionMapper,
                                  ModerationProperties properties,
                                  ObjectMapper objectMapper) {
        this.libraryMapper = libraryMapper;
        this.versionMapper = versionMapper;
        this.properties = properties;
        this.snapshotMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 快照文件磁盘模型（JSON 序列化契约；version 为装载时 sensitive_word_version 读数，
     * 仅作 loadVersion 兜底与排障参考）
     */
    public record WordlibSnapshot(long version, List<WordEntry> words) {
    }

    /**
     * 当前词库版本：查 sensitive_word_version（scene 主键）；查表失败回退快照版本并告警，
     * 无任何可用数据则抛异常（宁严勿松，交由调用方按启动期/运行期语义处理）
     */
    @Override
    public long loadVersion(String scene) {
        // Step1 正常路径：String → WordSceneEnum（非法值抛 IllegalArgumentException）→ 查版本表
        try {
            return selectVersion(WordSceneEnum.valueOf(scene));
        } catch (Exception e) {
            // Step2 兜底：查表失败回退本地快照版本（宁严勿松）；连快照都没有则抛异常拒绝装载
            WordlibSnapshot snapshot = readSnapshot(scene);
            if (snapshot != null) {
                log.error("词库版本查表失败，回退本地快照版本（宁严勿松）：scene={}, 快照 version={}",
                        scene, snapshot.version(), e);
                return snapshot.version();
            }
            throw new IllegalStateException("词库版本查表失败且无本地快照兜底，拒绝装载：scene=" + scene, e);
        }
    }

    /**
     * 装载该场景全部启用词条：查表成功且非空 → 持久化快照后返回；查表失败/为空 → 回退快照并告警；
     * 仍无可用数据抛异常（首次部署请先录入词库，满足「绝不静默空词库 default-allow」纪律）
     */
    @Override
    public List<WordEntry> loadWords(String scene) {
        // Step1 查表装载（异常吞掉记日志，与「为空」同入兜底分支，避免 DB 抖动误判为空词库）
        List<WordEntry> entries = null;
        try {
            entries = selectEnabledWords(scene);
        } catch (Exception e) {
            log.error("词库表查询失败：scene={}", scene, e);
        }
        // Step2 查表成功且非空：持久化快照后返回（快照语义 = 上次成功装载的非空词库）
        if (entries != null && !entries.isEmpty()) {
            persistSnapshot(scene, entries);
            return entries;
        }
        // Step3 查表失败/为空：回退本地快照（绝不静默空词库 default-allow）
        WordlibSnapshot snapshot = readSnapshot(scene);
        if (snapshot != null && snapshot.words() != null && !snapshot.words().isEmpty()) {
            log.error("词库表{}，已回退本地持久化快照（宁严勿松，请尽快核对词库表状态）：scene={}, 快照词数={}, 快照 version={}",
                    entries == null ? "查询失败" : "查询结果为空", scene, snapshot.words().size(), snapshot.version());
            return snapshot.words();
        }
        throw new IllegalStateException("词库装载失败且无可用本地快照，DFA 闸门将 default-allow，禁止放行：scene="
                + scene + (entries == null ? "（查表异常且无快照）" : "（词库表为空：首次部署请先录入该场景词库）"));
    }

    /**
     * 查启用词条（scene + status=1，黑+白名单一并返回，白名单分流由 DfaFacade 处理）
     */
    private List<WordEntry> selectEnabledWords(String scene) {
        // 只取装载所需三列（word/level/is_blacklist），条件：scene 精确匹配 + status=1 启用
        List<SensitiveWordLibrary> rows = libraryMapper.selectList(new LambdaQueryWrapper<SensitiveWordLibrary>()
                .select(SensitiveWordLibrary::getWord, SensitiveWordLibrary::getLevel,
                        SensitiveWordLibrary::getIsBlacklist)
                .eq(SensitiveWordLibrary::getScene, WordSceneEnum.valueOf(scene))
                .eq(SensitiveWordLibrary::getStatus, WordStatusEnum.ENABLED));
        // 实体行 → 契约词条（枚举转 int code，黑白名单一并返回，分流在 DfaFacade）
        return rows.stream()
                .map(r -> new WordEntry(r.getWord(), r.getLevel().getCode(),
                        r.getIsBlacklist() == WordListTypeEnum.BLACKLIST))
                .toList();
    }


    /**
     * 读版本（scene 主键行；无行/无版本值返回 -1）
     */
    private long selectVersion(WordSceneEnum scene) {
        // 仅取 version 一列；无行/值为 null 视为「无版本记录」返回 -1 占位
        SensitiveWordVersion row = versionMapper.selectOne(new LambdaQueryWrapper<SensitiveWordVersion>()
                .select(SensitiveWordVersion::getVersion)
                .eq(SensitiveWordVersion::getScene, scene));
        return row == null || row.getVersion() == null ? NO_VERSION : row.getVersion();
    }

    /**
     * 快照持久化（临时文件 + 原子 move 防截断）：仅在查表成功且非空时调用——
     * 快照语义是「上次成功装载的非空词库」，空表不覆盖旧快照（宁严勿松）
     */
    private void persistSnapshot(String scene, List<WordEntry> entries) {
        // 解析当前场景的正式快照文件路径
        Path file = snapshotFile(scene);
        // 临时文件引用提到 try 外：进程内异常时 catch 可 best-effort 删除，防 .tmp 残留累积
        Path tmp = null;
        try {
            // Step1 附带版本号（读取失败不阻断快照落盘，按 -1 占位仅损失排障信息）
            long version;
            try {
                version = selectVersion(WordSceneEnum.valueOf(scene));
            } catch (Exception e) {
                version = NO_VERSION;
                log.warn("快照版本号读取失败，快照按 version=-1 落盘（词表本体不受影响）：scene={}", scene);
            }
            // Step2 先写同目录临时文件（保证原子 move 可行），JSON 序列化
                    //实际路径来自配置项：{litisle.moderation.wordlib.snapshot-dir}/snapshot-{scene}.json
                    //正常情况：不会被显式删除，而是通过“原子 move”变成正式文件
                                //            从操作系统角度看：临时文件“改名”了，不是被删除
                                //            原子地重命名为：  snapshot-{scene}.json
                                //            异常情况：临时文件会残留，但不会被自动清理
                                //          **临时文件存储在快照目录中，内容是当前场景的词库 JSON 快照；
                                //            正常情况通过原子重命名“变成”正式快照文件，不会被删除；
            // 临时文件与正式快照同目录，成功时通过 ATOMIC_MOVE 原子重命名为 snapshot-{scene}.json；
            // 进程内异常时 catch 块 best-effort 删除半成品 .tmp；
            // kill -9 / 断电等崩溃残留由 @PostConstruct cleanupStaleTempSnapshots() 启动期全量清扫。

            // 确保快照目录存在（首次部署时目录可能尚未创建）
            Path dir = file.getParent();
            Files.createDirectories(dir);
            // 在快照同目录创建临时文件，保证后续原子 move 可行（同文件系统 rename）
            tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            // 将词库快照序列化为 JSON 写入临时文件（覆盖已有内容，避免追加脏数据）
            Files.write(tmp, snapshotMapper.writeValueAsBytes(new WordlibSnapshot(version, entries)),
                    StandardOpenOption.TRUNCATE_EXISTING);
            // Step3 原子替换旧快照（半途崩溃最多留临时文件，绝不留截断快照）；FS 不支持时降级普通 move
            try {
                // 原子地将临时文件重命名为正式快照文件：要么完整替换成功，要么旧快照纹丝不动；
                // 要求 tmp 与 file 同目录（同一文件系统），保证 ATOMIC_MOVE 不降级为 copy+delete
                //把临时文件移动/重命名为正式快照文件，如果正式文件已存在就覆盖它。
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                ///捕获"当前文件系统不支持 ATOMIC_MOVE"这个特定异常，走降级逻辑。
                // 降级普通 move：FS 不支持原子操作时兜底，非原子但保证最终一致
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("词库快照已持久化：scene={}, 词数={}, version={}", scene, entries.size(), version);
        } catch (Exception e) {
            // 进程内异常兜底：即时删除半成品临时文件（best-effort，删除失败仅告警不抛出，
            // 交由启动期清扫二次兑底），不阻断本次装载
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception cleanupEx) {
                    log.warn("临时快照文件清理失败（等待启动期清扫兑底）：{}", tmp, cleanupEx);
                }
            }
            log.error("词库快照持久化失败（不影响本次装载，但削弱下次故障兑底）：scene={}, file={}", scene, file, e);
        }
    }

    /**
     * 启动期清扫残留临时快照（进程崩溃兑底）：进程内异常已由 persistSnapshot catch 即时清理，
     * 但 kill -9/断电等崩溃残留的 .tmp 无 catch 可走，此处启动时统一清扫；
     * 快照目录为实例本地磁盘（snapshot-dir 单机私有），启动瞬间无并发写，直接删除全部 *.tmp 安全
     */
    @PostConstruct
    public void cleanupStaleTempSnapshots() {
        // 目录不存在说明从未落过快照，直接跳过
        Path dir = Path.of(properties.getWordlib().getSnapshotDir());
        if (!Files.isDirectory(dir)) {
            return;
        }
        // 扫描并删除全部 *.tmp 残留（单文件删除失败仅告警，不阻断启动，下次启动重试）
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    log.info("已清理残留临时快照文件：{}", p);
                } catch (Exception e) {
                    log.warn("残留临时快照文件清理失败（不影响启动）：{}", p, e);
                }
            });
        } catch (Exception e) {
            log.warn("快照目录扫描失败（不影响启动）：{}", dir, e);
        }
    }

    /**
     * 读快照：文件缺失/损坏一律返回 null（坏快照视同无快照，不放大故障），由调用方走无兑底分支
     */
    private WordlibSnapshot readSnapshot(String scene) {
        // 文件不存在直接视同无快照
        Path file = snapshotFile(scene);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            // 反序列化（向前兼容：未知字段不报错）
            return snapshotMapper.readValue(file.toFile(), WordlibSnapshot.class);
        } catch (Exception e) {
            // 坏快照视同无快照（不放大故障），调用方走无兜底分支
            log.error("词库快照读取/解析失败，视同无快照：scene={}, file={}", scene, file, e);
            return null;
        }
    }

    private Path snapshotFile(String scene) {
        // 快照文件路径：{snapshot-dir}/snapshot-{scene}.json（目录外置 litisle.moderation.wordlib.snapshot-dir）
        return Path.of(properties.getWordlib().getSnapshotDir(), "snapshot-" + scene + ".json");
    }

//┌──────────────────────────────────────────────────────────────┐
//        │  ① 数据库 (sensitive_word_library 表)                        │
//            │     word = "敏感词A", level = 2, is_blacklist = true          │
//            └──────────────────────────┬───────────────────────────────────┘
//            │ 读取
//                           ▼
//                                   ┌──────────────────────────────────────────────────────────────┐
//                                   │  ② TableWordLibraryLoader（你这个类）                         │
//            │     把 DB 行 → List<WordEntry>(word, level, blacklist)       │
//            │     + 顺手写一份 JSON 快照到磁盘                              │
//            │     ⚠️ 这里只是"搬运数据"，不构建 DFA，不做匹配               │
//            └──────────────────────────┬───────────────────────────────────┘
//            │ 返回 List<WordEntry>
//                           ▼
//                                   ┌──────────────────────────────────────────────────────────────┐
//                                   │  ③ DfaFacade（DFA 门面）                                     │
//            │     - 拿到 WordEntry 列表                                    │
//            │     - 按 isBlacklist 分流：黑名单 → houbb DFA 词库            │
//            │     - 调用 DFA 框架的 build/rebuild 方法                      │
//            │     ⚠️ 这里才真正构建 DFA 状态机（Trie 树）                   │
//            └──────────────────────────┬───────────────────────────────────┘
//            │ 构建完成
//                           ▼
//                                   ┌──────────────────────────────────────────────────────────────┐
//                                   │  ④ houbb DFA 引擎（第三方库 / 自研）                          │
//            │     - 把每个词拆成字符，建 AC 自动机 / Trie                    │
//            │     - 提供 match(text) → 返回命中的敏感词列表                  │
//            └──────────────────────────┬───────────────────────────────────┘
//            │ 调用
//                           ▼
//                                   ┌──────────────────────────────────────────────────────────────┐
//                                   │  ⑤ 审核业务代码                                              │
//            │     dfaFacade.match("用户输入的文本")                          │
//            │     → 返回命中了哪些敏感词 → 拦截/替换/告警                    │
//            └──────────────────────────────────────────────────────────────┘

}


