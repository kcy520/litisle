package com.litisle.litisleapi.dfa;

/**
 * 词库变更广播消息（Redis Pub 频道 review:wordlib:change 的 JSON 载荷，禁 Java 原生反序列化）
 *
 * <p>【消费纪律】订阅端（review/content/message 宿主 DFA 的服务）收到后调
 * {@link DfaFacade#rebuild}：内部 newVersion ≤ 当前版本则忽略（防 Pub 乱序/重复旧覆盖新），
 * 原子换引擎不中断在途查询（§2.5）</p>
 *
 * <p>【可靠性语义】Redis Pub 为 fire-and-forget：单条广播丢失时该实例沿用旧词库，
 * 由下次变更广播或实例重启收敛——rebuild 失败仅记日志，不影响消息循环</p>
 *
 * @param scene      词库场景（DfaScenes 归一后的 ARTICLE/COMMENT/USERNAME/MESSAGE）
 * @param version    本次变更后的 sensitive_word_version 版本
 * @param operatorId 变更操作人（审计排障用）
 * @param occurTime  变更提交时间戳（毫秒）
 */
public record WordlibChangedMessage(String scene, long version, Long operatorId, long occurTime) {
}