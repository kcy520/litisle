package com.litisle.litisleai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.dto.XiaoyueSuggestionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 建议态缓存服务（板块二 2.5 / P7 定档：建议态 Redis 缓存 key=requestId TTL 30 分钟）
 *
 * <p>【口径】只缓存成功产出的建议；缓存命中直接回查返回（幂等重试不重复计量、
 * 不重复调用模型）；缓存过期即弃（D4 建议态不落库——Redis 暂存不是落库，
 * 作者未确认的建议到期自动丢弃）。</p>
 *
 * <p>【降级语义】Redis 不可用时缓存读写静默跳过（best-effort），不阻断建议生成主链路。</p>
 *
 * <p>【通俗说明】AI 建议的"临时答案柜"：同一请求（requestId 相同）重试或重查时，
 * 先来这里看看有没有现成答案——有就直接返回，不再重复调模型花钱。三条规矩：
 * 只存成功的建议；30 分钟自动过期（作者没采用的建议到期自动丢弃，AI 建议永不
 * 落库）；Redis 挂了就当没有缓存，照常走生成链路，绝不因缓存故障影响主流程。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestionCacheService {

    /** 建议缓存 key 前缀（完整 key = 前缀 + requestId） */
    private static final String KEY_PREFIX = "ai:suggestion:";

    /** Redis 客户端（String 结构存 JSON） */
    private final StringRedisTemplate redisTemplate;

    /** Agent 配置（缓存开关与 TTL） */
    private final AiAgentProperties agentProperties;

    /** JSON 序列化器 */
    private final ObjectMapper objectMapper;

    /**
     * 按 requestId 回查缓存中的建议
     *
     * @param requestId 请求幂等键
     * @return 缓存的建议响应；未命中/开关关闭/Redis 异常返回 empty（走正常生成链路）
     *
     * <p>【通俗说明】查柜子：开关关着、没存过、过期了、读失败——都按"没缓存"
     * 处理，走正常生成。</p>
     */
    public Optional<XiaoyueSuggestionResponse> get(String requestId) {
        // 缓存开关关闭：直接走生成链路
        if (!agentProperties.isSuggestionCacheEnabled()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + requestId);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, XiaoyueSuggestionResponse.class));
        } catch (Exception e) {
            // 缓存读失败降级为未命中（best-effort，不阻断主链路）
            log.warn("建议缓存读取失败，按未命中处理 requestId={}", requestId, e);
            return Optional.empty();
        }
    }

    /**
     * 缓存一份成功产出的建议（TTL 外置配置，默认 30 分钟）
     *
     * @param requestId 请求幂等键
     * @param response  建议响应（degraded=false 的成功建议才调用本方法）
     *
     * <p>【通俗说明】往柜子里放一份成功建议（TTL 外置配置）；写失败只打日志——
     * 损失的只是幂等回查能力，建议本身照常返回给用户。</p>
     */
    public void put(String requestId, XiaoyueSuggestionResponse response) {
        // 缓存开关关闭：跳过（建议随响应返回后即弃，D4 不落库）
        if (!agentProperties.isSuggestionCacheEnabled()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(KEY_PREFIX + requestId, json,
                    Duration.ofMinutes(agentProperties.getSuggestionCacheTtlMinutes()));
        } catch (Exception e) {
            // 缓存写失败不影响建议返回（幂等回查能力降级，主链路不受影响）
            log.warn("建议缓存写入失败 requestId={}", requestId, e);
        }
    }
}
