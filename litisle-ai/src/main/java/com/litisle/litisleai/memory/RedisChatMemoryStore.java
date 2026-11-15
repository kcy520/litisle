package com.litisle.litisleai.memory;

import com.litisle.litisleai.config.AiAgentProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 会话记忆存储（板块三 3.2：LangChain4j ChatMemoryStore 的 Redis 版，衔接 2.1 底座）
 *
 * <p>【两层口径】DB（ai_session/ai_message）全量存档 + Memory（本类）滑窗暂存，
 * 两层口径不冲突：Redis 窗口服务模型上下文（MessageWindowChatMemory 最近 N 条），
 * DB 存档服务列表展示与审计；memoryId = 会话 ID（无会话请求用 requestId 一次性键，
 * 天然无跨请求记忆）。</p>
 *
 * <p>【降级铁律】Memory 不可用（Redis 异常）降级为无记忆单轮回答并明确标注
 * （log.warn 告警），不静默丢上下文、不中断回答主链路。</p>
 *
 * <p>【暂存口径】LRU 暂存：窗口由 MessageWindowChatMemory.maxMessages 控制（最近 N 条
 * 进模型），本类存窗口内消息并按 TTL 过期即弃（与 D4 建议态不落库同源——Redis 暂存
 * 不是落库）。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    /** 记忆暂存 key 前缀（完整 key = 前缀 + memoryId） */
    private static final String KEY_PREFIX = "ai:memory:";

    /** Redis 客户端（String 结构存消息 JSON） */
    private final StringRedisTemplate redisTemplate;

    /** Agent 配置（TTL 来源） */
    private final AiAgentProperties agentProperties;

    /**
     * 读取该记忆 ID 的窗口消息列表
     *
     * @param memoryId 记忆 ID（会话 ID 或一次性 requestId）
     * @return 消息列表；键不存在/Redis 异常降级返回空列表（无记忆单轮）
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + memoryId);
            if (json == null || json.isEmpty()) {
                return List.of();
            }
            return ChatMessageDeserializer.messagesFromJson(json);
        } catch (Exception e) {
            // 降级铁律：Memory 不可用降级为无记忆单轮回答并明确标注（不静默丢上下文——告警可见）
            log.warn("会话记忆读取失败，本轮降级为无记忆单轮回答 memoryId={}", memoryId, e);
            return List.of();
        }
    }

    /**
     * 覆盖写该记忆 ID 的窗口消息（MessageWindowChatMemory 每次追加后整体回写）
     *
     * @param memoryId 记忆 ID
     * @param messages 窗口内全部消息
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + memoryId,
                    ChatMessageSerializer.messagesToJson(messages),
                    Duration.ofMinutes(agentProperties.getMemoryTtlMinutes()));
        } catch (Exception e) {
            // 写失败不中断回答（本轮上下文仍可用，下次读取降级为无记忆并告警）
            log.warn("会话记忆写入失败，暂存降级 memoryId={} messageCount={}", memoryId, messages.size(), e);
        }
    }

    /**
     * 删除该记忆 ID 的暂存（会话删除受理时联动清理，防已删会话残留暂存）
     *
     * @param memoryId 记忆 ID
     */
    @Override
    public void deleteMessages(Object memoryId) {
        try {
            redisTemplate.delete(KEY_PREFIX + memoryId);
        } catch (Exception e) {
            // 删失败由 TTL 兜底过期，不阻断删除受理主链路
            log.warn("会话记忆删除失败（TTL 兜底过期）memoryId={}", memoryId, e);
        }
    }

    /**
     * 会话删除受理联动：按会话 ID 清理暂存（供 AiSessionService 调用）
     *
     * @param sessionId 会话 ID
     */
    public void evictSessionMemory(Long sessionId) {
        deleteMessages(String.valueOf(sessionId));
    }
}
