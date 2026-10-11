package com.litisle.litisleauth.dfa;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.WordEntry;
import com.litisle.litisleapi.dfa.WordLibraryLoader;
import com.litisle.litisleapi.dfa.WordlibSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * auth 宿主词库装载器（Redis 快照优先 + 本地持久化快照兜底，§4.4.4 宁严勿松）
 *
 * 【数据源】review 侧词表变更事务提交后写入 Redis 快照 review:wordlib-snapshot:{scene}
 * （WordlibSnapshot JSON 契约，禁 Java 原生反序列化）；auth 无 DB，直连词表不可行
 *
 * 【本地兜底】每次成功读 Redis 快照后同步落本地文件（{snapshot-dir}/snapshot-{scene}.json
 * 临时文件 + 原子重命名）；Redis 不可用/快照缺失时回退读本地上次成功词库并告警——
 * 绝不静默返回空列表造成 default-allow；两级均失败抛异常（启动期中断启动，
 * 运行期 rebuild 失败沿用旧引擎，在途查询不受影响）
 *
 * @author litisle
 */
@Slf4j
@Component
public class RedisWordLibraryLoader implements WordLibraryLoader {

    /** Redis 词库快照读写模板（key 口径与 review 侧写入口径一致） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 快照 JSON 解析器（copy 副本隔离，防全局 ObjectMapper 定制污染快照契约） */
    private final ObjectMapper snapshotMapper;

    /** 本地持久化快照目录（Redis 故障兜底，外置配置可调） */
    private final Path snapshotDir;

    public RedisWordLibraryLoader(StringRedisTemplate stringRedisTemplate,
                                  ObjectMapper objectMapper,
                                  @Value("${litisle.moderation.wordlib.snapshot-dir:${user.home}/.litisle/auth/dfa-snapshot}")
                                  String snapshotDir) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.snapshotMapper = objectMapper.copy();
        this.snapshotDir = Path.of(snapshotDir);
    }

    /**
     * 当前词库版本：Redis 快照 → 本地兜底 → 均不可用返回 -1（调用方 rebuild 侧按未知态处理）
     */
    @Override
    public long loadVersion(String scene) {
        // 读取快照拿版本（两级数据源在 readSnapshot 内收敛）
        WordlibSnapshot snapshot = readSnapshot(scene);
        return snapshot == null ? -1L : snapshot.version();
    }

    /**
     * 装载场景全量词条：快照缺失/空词库直接抛异常（空引擎=default-allow 放行，宁严勿松禁止）
     */
    @Override
    public List<WordEntry> loadWords(String scene) {
        WordlibSnapshot snapshot = readSnapshot(scene);
        if (snapshot != null && snapshot.words() != null && !snapshot.words().isEmpty()) {
            return snapshot.words();
        }
        throw new IllegalStateException("词库快照不可用或为空，拒绝构建空引擎（default-allow 防线）：scene=" + scene);
    }

    /**
     * 两级读取：Redis 快照（review 维护的主数据源）→ 本地持久化快照（上次成功词库）
     */
    private WordlibSnapshot readSnapshot(String scene) {
        // 一级：Redis 快照
        try {
            String json = stringRedisTemplate.opsForValue()
                    .get(String.format(ModerationConstant.WORDLIB_SNAPSHOT_KEY, scene));
            if (json != null && !json.isBlank()) {
                WordlibSnapshot snapshot = snapshotMapper.readValue(json, WordlibSnapshot.class);
                persistLocalSnapshot(scene, json);
                return snapshot;
            }
            log.warn("Redis 词库快照缺失：scene={}，回退本地持久化快照", scene);
        } catch (Exception e) {
            log.error("Redis 词库快照读取失败：scene={}，回退本地持久化快照", scene, e);
        }
        // 二级：本地持久化快照（上次成功词库，Redis 故障兜底）
        return readLocalSnapshot(scene);
    }

    /**
     * 成功读取 Redis 快照后同步落本地文件（临时文件写 + ATOMIC_MOVE 原子重命名，防半写）；
     * 兜底写失败仅 warn 不影响主流程（本地快照是兜底项，非主数据源）
     */
    private void persistLocalSnapshot(String scene, String json) {
        try {
            Files.createDirectories(snapshotDir);
            Path target = snapshotFile(scene);
            Path tmp = snapshotDir.resolve("snapshot-" + scene + ".json.tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("本地词库快照写入失败（兜底能力降级，不影响主流程）：scene={}", scene, e);
        }
    }

    /**
     * 读本地持久化快照：文件缺失返回 null（由 loadWords 抛异常 fail-fast），解析失败亦返回 null
     */
    private WordlibSnapshot readLocalSnapshot(String scene) {
        // 本地快照文件：{snapshot-dir}/snapshot-{scene}.json
        Path file = snapshotFile(scene);
        if (!Files.exists(file)) {
            log.error("本地词库快照缺失（两级数据源均不可用）：scene={}", scene);
            return null;
        }
        try {
            WordlibSnapshot snapshot = snapshotMapper.readValue(file.toFile(), WordlibSnapshot.class);
            log.warn("已回退本地持久化词库快照：scene={}, version={}, 词数={}",
                    scene, snapshot.version(), snapshot.words() == null ? 0 : snapshot.words().size());
            return snapshot;
        } catch (Exception e) {
            log.error("本地词库快照解析失败（视为不可用）：scene={}", scene, e);
            return null;
        }
    }

    /**
     * 本地快照文件路径（目录外置 litisle.moderation.wordlib.snapshot-dir，默认 user.home 下）
     */
    private Path snapshotFile(String scene) {
        return snapshotDir.resolve("snapshot-" + scene + ".json");
    }
}