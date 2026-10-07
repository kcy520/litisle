package com.litisle.litisleapi.dfa;

import java.util.List;

/**
 * 词库快照（Redis 快照与 review 本地持久化快照共用的 JSON 契约，禁 Java 原生反序列化）
 *
 * <p>【读写分工】review 侧词表变更事务提交后写入（先于 Pub 广播发布，保证订阅端重建时读到新词库）：
 * ① Redis key：review:wordlib-snapshot:{scene}（content/message 的 WordLibraryLoader 读 Redis 快照实现）；
 * ② review 本地文件快照：litisle.moderation.wordlib.snapshot-dir（表加载失败兜底，宁严勿松）</p>
 *
 * <p>version 为装载时 sensitive_word_version 读数（scene 级 SSOT）；words 为黑+白名单全量词条
 * （blacklist 分流语义见 {@link WordEntry}）</p>
 */
public record WordlibSnapshot(long version, List<WordEntry> words) {
}