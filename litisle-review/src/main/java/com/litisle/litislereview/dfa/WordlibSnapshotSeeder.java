package com.litisle.litislereview.dfa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litisleapi.dfa.WordLibraryLoader;
import com.litisle.litisleapi.dfa.WordlibSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 词库快照播种器（冷启动缺口修复）(修复auth模块启动爆粗）
 *
 * 【背景】auth 侧词库两级数据源 = Redis 快照（主）+ 本地兜底文件（从上次成功读取落盘）。
 * 快照仅由 WordlibVersionService 在「词表变更事务提交后」写入，新环境/Redis 清空后
 * 快照不存在 → auth 兜底文件也从未落盘 → auth initAll fail-fast 起不来（冷启动死锁）
 *
 * 【职责】review 启动全单例就绪后，逐文本场景检查 Redis 快照 key：缺失则从 DB 装载
 * （复用 WordLibraryLoader 查表路径，版本/词条口径与变更发布完全一致）并写入快照；
 * 已存在则跳过（幂等，不覆盖更新版本，避免与变更链路乱序竞争）
 *
 * 【时序】SmartInitializingSingleton 在全部单例就绪后执行；播种与 review 自身
 * dfaInitializer 的引擎构建互不依赖（播种直读 loader，不经过 DfaFacade）
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WordlibSnapshotSeeder implements SmartInitializingSingleton {

    /** Redis 读写模板（key 口径与变更发布链路一致：review:wordlib-snapshot:{scene}） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 词库装载器（查表实现，DB 为准） */
    private final WordLibraryLoader wordLibraryLoader;

    /** 快照 JSON 序列化器（WordlibSnapshot 契约） */
    private final ObjectMapper objectMapper;

    /**
     * 全单例就绪后逐文本场景播种缺失的 Redis 快照（幂等：仅补缺失，不覆盖已有快照）
     */
    @Override
    public void afterSingletonsInstantiated() {
        // 逐文本场景检查并播种；任一场景播种失败抛异常中断启动（与 initAll fail-fast 口径一致）
        for (String scene : DfaScenes.textScenes()) {
            seedIfAbsent(scene);
        }
        log.info("Redis 词库快照播种检查完成，文本场景：{}", DfaScenes.textScenes());
    }

    /**
     * 单场景播种：快照 key 已存在则跳过；缺失则从 DB 装载词条+版本后写入 Redis 快照
     *
     * @param scene 文本场景（ARTICLE/COMMENT/USERNAME/MESSAGE）
     */
    private void seedIfAbsent(String scene) {
        // 快照 key（与 WordlibVersionService 发布口径一致）
        String key = String.format(ModerationConstant.WORDLIB_SNAPSHOT_KEY, scene);
        // 已有快照：变更链路维护中，播种器不介入（幂等，防乱序覆盖新版本）
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            log.debug("Redis 词库快照已存在，跳过播种：scene={}", scene);
            return;
        }
        try {
            // 从 DB 装载全量词条与版本（复用查表路径，口径与变更发布一致）
            List<com.litisle.litisleapi.dfa.WordEntry> words = wordLibraryLoader.loadWords(scene);
            long version = wordLibraryLoader.loadVersion(scene);
            // 写 Redis 快照（WordlibSnapshot JSON 契约，禁 Java 原生反序列化）
            String snapshotJson = objectMapper.writeValueAsString(new WordlibSnapshot(version, words));
            stringRedisTemplate.opsForValue().set(key, snapshotJson);
            log.info("Redis 词库快照播种完成：scene={}, version={}, 词数={}", scene, version, words.size());
        } catch (Exception e) {
            // 播种失败 fail-fast：说明 DB 词库本身不可用，review 自身 initAll 也过不了，尽早暴露
            throw new IllegalStateException("Redis 词库快照播种失败：scene=" + scene, e);
        }
    }
}