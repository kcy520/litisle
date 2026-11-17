package com.litisle.litisleai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleai.config.AiAgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 会话条件上下文存储服务（板块十 2.1：会话内多轮调条件的上一轮条件 JSON 暂存）
 *
 * <p>【职责边界】本类只负责按 sessionId 暂存/读取/清理「上一轮条件提取 JSON」，
 * 条件的合法性由 ConditionExtractService 按注册表校验，合并语义由增量提取 prompt
 * 收口（本轮明确修改覆盖上轮、未提及沿用上轮）。与 SessionResultRegistryService
 * （序号映射）平行，同属会话维度暂存，互不依赖。</p>
 *
 * <p>【会话铁律】按 sessionId 维度隔离，跨会话不可引用；TTL 外置（复用
 * litisle.ai.agent.result-set-ttl-minutes 会话暂存口径）；Redis 异常降级为无上下文
 * （下一轮按普通提取处理）并告警，不中断推荐主链路；会话删除联动清理（evict）。</p>
 *
 * <p>【通俗说明】会话里"上一轮筛选条件"的便签夹：用户多轮调整搜索条件时（先说
 * "找科幻文"，再说"改成搞笑的"），上一轮的条件 JSON 就夹在这里，下一轮提取时
 * 拿出来当"合并基线"。三条规矩：按会话隔离（A 会话的条件 B 会话用不了）、到时
 * 自动作废（TTL）、Redis 挂了也不碍事——存读失败都静默降级为"没有上下文"，
 * 下一轮按全新条件处理，绝不因此报错打断推荐。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionConditionStoreService {

    /** 条件上下文暂存 key 前缀（完整 key = 前缀 + sessionId） */
    private static final String KEY_PREFIX = "ai:cond-extract:";

    /** Redis 客户端（String 结构存条件 JSON） */
    private final StringRedisTemplate redisTemplate;

    /** Agent 配置（TTL 外置来源，复用会话暂存 TTL 口径） */
    private final AiAgentProperties agentProperties;

    /** JSON 解析器（条件对象序列化/反序列化） */
    private final ObjectMapper objectMapper;

    /**
     * 保存会话上一轮条件 JSON（整体覆盖写 + 刷新 TTL）
     *
     * @param sessionId     会话 ID（null=一次性请求，不保存）
     * @param conditionJson 已通过注册表校验的条件 JSON（作为下一轮合并基线）
     *
     * <p>【通俗说明】往便签夹里夹一张：一次性请求（没会话）直接跳过；保存失败只
     * 打日志不报错——丢了上下文顶多下一轮从头提取。</p>
     */
    public void save(Long sessionId, String conditionJson) {
        // 一次性请求无会话维度：不保存（跨会话不可引用铁律的入口防线）
        if (sessionId == null || conditionJson == null || conditionJson.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, conditionJson,
                    Duration.ofMinutes(agentProperties.getResultSetTtlMinutes()));
            log.info("会话条件上下文已保存 sessionId={} jsonChars={} ttlMinutes={}",
                    sessionId, conditionJson.length(), agentProperties.getResultSetTtlMinutes());
        } catch (Exception e) {
            // 保存失败不阻断推荐主链路：下一轮按普通提取处理（无合并基线）
            log.warn("会话条件上下文保存失败（下一轮按普通提取处理）sessionId={}", sessionId, e);
        }
    }

    /**
     * 读取会话上一轮条件 JSON（作为本轮增量提取的合并基线）
     *
     * @param sessionId 会话 ID（null 直接返回 null——一次性请求无上下文）
     * @return 上一轮条件 JSON；不存在（首轮/过期/异常）返回 null（按普通提取处理）
     *
     * <p>【通俗说明】从便签夹里取上一轮的条件：没会话、没存过、过期了、Redis 挂了
     * 都返回 null，调用方按"首轮"处理。</p>
     */
    public String load(Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            // 读取失败降级为无上下文并告警：下一轮按普通提取处理，不中断推荐主链路
            log.warn("会话条件上下文读取失败（按普通提取处理）sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 会话删除受理联动清理（防已删会话残留条件上下文；供会话治理调用）
     *
     * @param sessionId 会话 ID
     *
     * <p>【通俗说明】会话删除时把便签抽掉：防止已删会话还残留条件上下文；
     * 删失败也不慌，有 TTL 自动过期兜底。</p>
     */
    public void evict(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            // 删失败由 TTL 兜底过期，不阻断删除受理主链路
            log.warn("会话条件上下文联动清理失败（TTL 兜底过期）sessionId={}", sessionId, e);
        }
    }
}
