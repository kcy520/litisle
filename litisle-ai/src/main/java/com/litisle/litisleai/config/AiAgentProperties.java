package com.litisle.litisleai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * 通俗说明：
 * 这是"小悦"助手的行为参数总表：最多来回推理几轮、摘要最多多少字、最多生成几个标签、
 * 建议结果缓存多久、发出去的内容要不要脱敏、每个用户最多存几个会话、
 * 单个会话最多几条消息、会话保留多少天后清理、每次带给模型几条历史对话。
 * 只放行为参数；连哪个模型、用什么密钥这类连接参数在 AiProviderProperties 里。
 */

/**
 * 小悦 Agent 侧配置（板块二：循环上限 / 结构化校验 / 建议缓存 / 出站脱敏开关，全部外置 yml/Nacos）
 *
 * <p>【配置】前缀 litisle.ai.agent；本类只承载 Agent 编排的运行参数，
 * 模型连接参数在 {@link AiProviderProperties}（通道层），两配置源不重叠。</p>
 *
 * @author litisle
 */
@Data
@ConfigurationProperties(prefix = "litisle.ai.agent")
public class AiAgentProperties {

    /**
     * Agent 推理循环上限（初值 5 轮，next_update 2.1 定档）：防死循环与成本放大；
     * 超限抛明确异常，由 service 降级为固定管道直连
     */
    private int maxLoopRounds = 5;

    /** 摘要字数上限（2.4/2.5 定档：摘要 ≤ 500 字，service 侧校验不依赖模型自觉） */
    private int summaryMaxChars = 500;

    /** 结构化输出自校验重试预算（2.4 定档固定 1 次，防反思循环成本放大） */
    private int structuredRetryBudget = 1;

    /** 标签建议数量上限（AI 不新造标签，越池校验兜底；数量上限防超长输出） */
    private int maxTagCount = 5;

    /**
     * 建议态 Redis 缓存开关（P7 定档：key=requestId TTL 30 分钟，幂等回查不重复计量）
     */
    private boolean suggestionCacheEnabled = true;

    /** 建议态缓存 TTL（分钟，P7 定档 30 分钟） */
    private int suggestionCacheTtlMinutes = 30;

    /**
     * 出站脱敏开关（P1 定档：稿件外发保留配置开关，可一键关闭；true=provider 出站前统一脱敏）
     */
    private boolean desensitizeEnabled = true;

    // ==================== 会话治理（板块三 3.1/3.2，D25 口径） ====================

    /** 暂存区会话上限（D25：90 天内 200 会话上限，超限按 last_active_time LRU 淘汰最久未活跃） */
    private int sessionStashLimit = 200;

    /** 单会话消息条数上限（D25：单会话 500 条上限，超限拒绝追加并提示开新会话） */
    private int sessionMessageLimit = 500;

    /** 会话留存天数（D25/D6：生成内容留存 90 天，到期由定时兜底任务清理） */
    private int sessionRetentionDays = 90;

    /** 软删会话物理清理延迟天数（懒删之外定时兜底物理删除 del_flag=1 的行与消息） */
    private int sessionPurgeDelayDays = 7;

    /** Redis ChatMemory 窗口大小（Design-Document §2.3.6：LRU 暂存最近 N 条进模型上下文） */
    private int memoryWindowSize = 10;

    /** Redis ChatMemory 键 TTL（分钟，暂存上下文过期即弃；D4 建议态不落库同源口径） */
    private int memoryTtlMinutes = 30;
}
