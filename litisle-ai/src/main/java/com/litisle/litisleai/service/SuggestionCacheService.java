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
