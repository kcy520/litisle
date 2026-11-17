package com.litisle.litisleai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleai.config.AiAgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话结果集注册服务（板块九 1.1 公共地基：会话内「最近一次推荐/检索结果集」的
 * articleId→序号映射维护）
 *
 * <p>【职责边界】本类只维护 Redis 暂存的序号映射（key=ai:result-set:{sessionId}），
 * 推荐结果是否可推荐由检索与回查链路保证（SimilarRecommendService），映射过期后
 * 序号不可解析——调用方必须明确提示重新发起检索，不猜不编造。所有「对结果下指令」
 * 类功能（结果动作/读后服务）均以本注册为序号定位前提。</p>
 *
 * <p>【会话铁律】映射按 sessionId 维度隔离：解析必须携带同一 sessionId，跨会话
 * 不可引用（工具层从 AgentCallContext 取当前会话，一次性请求无会话即不可用）；
 * TTL 外置（litisle.ai.agent.result-set-ttl-minutes），Redis 异常降级为不可解析并
 * 告警，不中断会话主链路。</p>
 *
 * <p>【记录口径】仅记录非空结果集（检索无命中视为「最近一次无结果」，覆盖写为空集
 * 使旧序号失效，防引用到上一次的陈旧集合）；每次记录整体覆盖写并刷新 TTL。</p>
 *
 * <p>【通俗说明】会话里的"编号登记簿"：每次推荐/检索把结果按展示顺序登记成
 * "第 1 篇、第 2 篇……"，之后用户说"帮我总结第 2 篇"就能靠序号找到具体文章。
 * 三条规矩：按会话隔离、到时作废（TTL）、检索无命中时清空登记簿（防止旧序号
 * 指到上一轮的陈旧文章）。所有"对结果下指令"的功能（结果动作、读后追问）都以
 * 本登记为前提；解析不到就明确让用户重新检索，绝不瞎猜。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionResultRegistryService {

    /** 结果集暂存 key 前缀（完整 key = 前缀 + sessionId） */
    private static final String KEY_PREFIX = "ai:result-set:";

    /** Redis 客户端（String 结构存 articleId 有序 JSON 数组） */
    private final StringRedisTemplate redisTemplate;

    /** Agent 配置（TTL 外置来源） */
    private final AiAgentProperties agentProperties;

    /** JSON 解析器（articleId 数组序列化/反序列化） */
    private final ObjectMapper objectMapper;

    /**
     * 记录会话最近一次推荐/检索结果集（整体覆盖写 + 刷新 TTL）
     *
     * <p>【业务逻辑步骤】1. 空结果集 → 删除既有映射（旧序号失效防陈旧引用）；
     * 2. 非空结果集 → 按展示顺序序列化为 articleId 数组覆盖写，TTL 外置刷新。</p>
     *
     * @param sessionId  会话 ID（null=一次性请求，不记录）
     * @param articleIds 结果集文章 ID 列表（按展示顺序，序号 = 下标 + 1）
     *
     * <p>【通俗说明】登记本次结果集：空结果集就清空旧登记（旧序号全部失效）；
     * 非空就按展示顺序整本覆盖写并刷新有效期。记录失败只打日志——序号定位只是
     * 增强能力，缺了它功能降级但不崩。</p>
     */
    public void record(Long sessionId, List<Long> articleIds) {
        // 一次性请求无会话维度：不记录（跨会话不可引用铁律的入口防线）
        if (sessionId == null) {
            return;
        }
        try {
            String key = KEY_PREFIX + sessionId;
            // 空结果集 = 最近一次检索无命中：清空映射使旧序号失效，防引用陈旧集合
            if (articleIds == null || articleIds.isEmpty()) {
                redisTemplate.delete(key);
                log.info("会话结果集已清空（最近一次检索无命中）sessionId={}", sessionId);
                return;
            }
            // 按展示顺序覆盖写（序号 = 数组下标 + 1），TTL 外置刷新
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(articleIds),
                    Duration.ofMinutes(agentProperties.getResultSetTtlMinutes()));
            log.info("会话结果集已记录 sessionId={} count={} ttlMinutes={}",
                    sessionId, articleIds.size(), agentProperties.getResultSetTtlMinutes());
        } catch (Exception e) {
            // 记录失败不阻断推荐主链路：结果动作只是增强能力，映射缺失时工具明确提示
            log.warn("会话结果集记录失败（结果动作将不可用）sessionId={}", sessionId, e);
        }
    }

    /**
     * 按序号解析会话结果集文章 ID（过期/越界返回 empty，调用方明确提示重新检索）
     *
     * @param sessionId 会话 ID（null 直接 empty——一次性请求无结果集）
     * @param seq       序号（从 1 起）
     * @return 文章 ID；结果集不存在（过期/未记录）、序号越界返回 empty
     *
     * <p>【通俗说明】"第 N 篇是哪篇？"：从登记簿查序号对应的文章。没会话、序号
     * 非法、登记簿过期、序号超出范围——统统返回 empty，调用方统一按"请重新检索"
     * 提示，不猜不编造。</p>
     */
    public Optional<Long> resolve(Long sessionId, int seq) {
        // 一次性请求/非法序号：直接不可解析（不猜不编造）
        if (sessionId == null || seq < 1) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            if (json == null || json.isEmpty()) {
                // 键不存在 = 结果集过期或从未记录：统一按过期语义提示重新检索
                return Optional.empty();
            }
            List<Long> articleIds = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(ArrayList.class, Long.class));
            if (seq > articleIds.size()) {
                return Optional.empty();
            }
            return Optional.of(articleIds.get(seq - 1));
        } catch (Exception e) {
            // Redis/解析异常降级为不可解析并告警：不中断会话，序号引用按过期提示处理
            log.warn("会话结果集解析失败（按过期提示处理）sessionId={} seq={}", sessionId, seq, e);
            return Optional.empty();
        }
    }

    /**
     * 查询会话结果集规模（序号越界提示与动作批量校验用）
     *
     * @param sessionId 会话 ID
     * @return 结果集文章数；不存在/异常返回 0
     *
     * <p>【通俗说明】数一数登记簿里登记了几篇文章（序号越界提示和批量校验用）。</p>
     */
    public int size(Long sessionId) {
        if (sessionId == null) {
            return 0;
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            if (json == null || json.isEmpty()) {
                return 0;
            }
            List<Long> articleIds = objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(ArrayList.class, Long.class));
            return articleIds.size();
        } catch (Exception e) {
            log.warn("会话结果集规模查询失败 sessionId={}", sessionId, e);
            return 0;
        }
    }

    /**
     * 会话删除受理联动清理（防已删会话残留结果集映射；供会话治理调用）
     *
     * @param sessionId 会话 ID
     *
     * <p>【通俗说明】会话删除时撕掉登记页：防已删会话残留序号映射；删失败由
     * TTL 自动过期兜底。</p>
     */
    public void evict(Long sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (Exception e) {
            // 删失败由 TTL 兜底过期，不阻断删除受理主链路
            log.warn("会话结果集联动清理失败（TTL 兜底过期）sessionId={}", sessionId, e);
        }
    }
}
