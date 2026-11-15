package com.litisle.litisleai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.domain.entity.AiMessage;
import com.litisle.litisleai.domain.entity.AiSession;
import com.litisle.litisleai.mapper.AiMessageMapper;
import com.litisle.litisleai.mapper.AiSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 会话治理服务（板块三 3.1：创建/归属校验/消息落库/LRU 淘汰/用户删除受理/留存清理）
 *
 * <p>【职责边界】本类收口会话与消息的全部读写（Memory 的 Redis 暂存由 RedisChatMemoryStore
 * 承担，本类是 DB 全量存档层；两层口径不冲突——Redis 窗口服务模型上下文，DB 存档服务
 * 列表展示与审计）。Agent 不直接写本表，消息落库由 service 层编排。</p>
 *
 * <p>【治理口径（D25）】暂存区（is_lru=1）200 会话上限，超限按 last_active_time LRU
 * 淘汰最久未活跃（软删 + 消息级联懒删）；单会话 500 条消息上限，超限拒绝追加并提示
 * 开新会话；留存 90 天由定时兜底任务清理（AiSessionCleanupTask）。</p>
 *
 * <p>【删除铁律（R9 缺口补齐）】删除请求由 AI 侧受理（会话数据持有方）：软删懒删
 * （del_flag=1，会话行保留作审计留痕）+ 定时兜底物理清理（软删 7 天后删行删消息），
 * 防用户停用后超期留存。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSessionService {

    /** Agent 类型：小悦 */
    public static final String AGENT_TYPE_XIAOYUE = "XIAOYUE";

    /** 消息角色：用户 */
    public static final String ROLE_USER = "USER";

    /** 消息角色：助手 */
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    /** 消息角色：系统 */
    public static final String ROLE_SYSTEM = "SYSTEM";

    /** 暂存区标记 */
    private static final int LRU_STASH = 1;

    /** 未删除标记 */
    private static final int DEL_FLAG_NORMAL = 0;
    private static final int DEL_FLAG_DELETED = 1;

    /** 会话 Mapper */
    private final AiSessionMapper sessionMapper;

    /** 消息 Mapper */
    private final AiMessageMapper messageMapper;

    /** Agent 配置（治理参数来源） */
    private final AiAgentProperties agentProperties;

    /** Redis 会话记忆存储（删除受理联动清理暂存，板块三 3.2） */
    private final com.litisle.litisleai.memory.RedisChatMemoryStore redisChatMemoryStore;

    /**
     * 创建会话（暂存区默认）；暂存区达上限时 LRU 淘汰最久未活跃会话（软删懒删）
     *
     * @param userId    用户ID
     * @param agentType Agent 类型（XIAOYUE）
     * @param mode      小悦模式（POLISH/STRUCTURE/SUMMARY/DEEP，可空）
     * @param title     会话标题（首条消息摘要，可空）
     * @return 新建会话实体（含自增 ID）
     */
    public AiSession createSession(Long userId, String agentType, String mode, String title) {
        // LRU 淘汰前置：达上限先腾位（淘汰失败不阻断创建——治理参数未定档时上限内自然放行）
        evictLruIfNeeded(userId);
        AiSession session = new AiSession();
        session.setUserId(userId);
        session.setAgentType(agentType);
        session.setMode(mode);
        // 标题截断到 DDL 长度（首条消息摘要）
        session.setTitle(truncateTitle(title));
        session.setIsLru(LRU_STASH);
        session.setDelFlag(DEL_FLAG_NORMAL);
        session.setLastActiveTime(LocalDateTime.now());
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 归属校验读取会话（会话不存在/不属于该用户/已删除均拒绝）
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @return 会话实体
     * @throws BizException 会话不存在或无权访问（10001 参数错误口径）
     */
    public AiSession requireOwnedSession(Long userId, Long sessionId) {
        AiSession session = sessionMapper.selectById(sessionId);
        // 归属校验：跨用户访问按不存在处理（不泄露会话存在性）
        if (session == null || session.getDelFlag() != DEL_FLAG_NORMAL || !session.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "会话不存在或无权访问");
        }
        return session;
    }

    /**
     * 会话列表（GET /ai/sessions：该用户全部未删会话，按最后活跃时间倒序）
     *
     * @param userId 用户ID
     * @return 会话列表（含已保存区与暂存区）
     */
    public List<AiSession> listSessions(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getUserId, userId)
                .eq(AiSession::getDelFlag, DEL_FLAG_NORMAL)
                .orderByDesc(AiSession::getLastActiveTime));
    }

    /**
     * 追加一条消息（单会话 500 条上限校验，超限拒绝并提示开新会话）
     *
     * @param sessionId  会话ID
     * @param role       角色（USER/ASSISTANT）
     * @param content    消息内容
     * @param tokenCnt   token 数（未接估算器传 0）
     * @param promptTokens    该轮输入 token（仅终答行记录，其余传 null）
     * @param completionTokens 该轮输出 token（仅终答行记录，其余传 null）
     * @throws BizException 超过单会话消息上限（10001）
     */
    public void appendMessage(Long sessionId, String role, String content,
                              Integer tokenCnt, Integer promptTokens, Integer completionTokens) {
        // 消息上限校验：D25 单会话 500 条上限（分页插件 count，不拼 LIMIT 串）
        long count = messageMapper.selectCount(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getSessionId, sessionId));
        if (count >= agentProperties.getSessionMessageLimit()) {
            log.warn("单会话消息数达上限，拒绝追加 sessionId={} count={} limit={}",
                    sessionId, count, agentProperties.getSessionMessageLimit());
            throw new BizException(ErrorCode.PARAM_ERROR, "该会话消息已达上限，请开启新会话");
        }
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setTokenCnt(tokenCnt == null ? 0 : tokenCnt);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        messageMapper.insert(message);
    }

    /**
     * 会话活跃刷新（LRU 依据：每次对话 touch 一次）
     *
     * @param sessionId 会话ID
     */
    public void touch(Long sessionId) {
        AiSession update = new AiSession();
        update.setId(sessionId);
        update.setLastActiveTime(LocalDateTime.now());
        // update-strategy=not_null 下仅非空字段更新
        sessionMapper.updateById(update);
    }

    /**
     * 用户删除会话（删除请求由 AI 侧受理：软删懒删 + 会话行保留作审计留痕；
     * 消息不立即删——由定时兜底任务物理清理，防删失败丢审计链；Redis 暂存联动清理）
     *
     * @param userId    用户ID（归属校验）
     * @param sessionId 会话ID
     * @throws BizException 会话不存在或无权访问
     */
    public void deleteSession(Long userId, Long sessionId) {
        // 归属校验（复用 requireOwnedSession：跨用户/已删按不存在处理）
        requireOwnedSession(userId, sessionId);
        AiSession update = new AiSession();
        update.setId(sessionId);
        update.setDelFlag(DEL_FLAG_DELETED);
        sessionMapper.updateById(update);
        // Redis 记忆暂存联动清理（store 内部异常自兜底，不阻断删除受理）
        redisChatMemoryStore.evictSessionMemory(sessionId);
        // 审计留痕：软删动作落日志（会话行保留，物理清理前可追溯操作时间线）
        log.info("用户删除会话（软删懒删受理）userId={} sessionId={}", userId, sessionId);
    }

    /**
     * LRU 淘汰（创建前置）：暂存区达上限时按 last_active_time 淘汰最久未活跃会话（软删）
     *
     * @param userId 用户ID
     */
    private void evictLruIfNeeded(Long userId) {
        // 上限未配置（≤0）不治理
        int limit = agentProperties.getSessionStashLimit();
        if (limit <= 0) {
            return;
        }
        // 分页插件取前 1 条最久未活跃（不拼 LIMIT 串）；count 判是否达上限
        long count = sessionMapper.selectCount(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getUserId, userId)
                .eq(AiSession::getIsLru, LRU_STASH)
                .eq(AiSession::getDelFlag, DEL_FLAG_NORMAL));
        if (count < limit) {
            return;
        }
        Page<AiSession> page = sessionMapper.selectPage(new Page<>(1, 1),
                new LambdaQueryWrapper<AiSession>()
                        .eq(AiSession::getUserId, userId)
                        .eq(AiSession::getIsLru, LRU_STASH)
                        .eq(AiSession::getDelFlag, DEL_FLAG_NORMAL)
                        .orderByAsc(AiSession::getLastActiveTime));
        if (page.getRecords().isEmpty()) {
            return;
        }
        // 淘汰 = 软删懒删（消息由定时兜底任务物理清理）
        AiSession oldest = page.getRecords().get(0);
        AiSession update = new AiSession();
        update.setId(oldest.getId());
        update.setDelFlag(DEL_FLAG_DELETED);
        sessionMapper.updateById(update);
        log.info("暂存区达上限 LRU 淘汰最久未活跃会话 userId={} evictedSessionId={}", userId, oldest.getId());
    }

    /**
     * 标题截断到 DDL 长度（varchar(100)，按字符截断）
     */
    private String truncateTitle(String title) {
        if (title == null) {
            return null;
        }
        return title.length() <= 100 ? title : title.substring(0, 100);
    }

    /**
     * 会话绑定信息（板块三 3.2：Agent memoryId 与 DB 会话的对应关系载体）
     *
     * <p>memoryId = String.valueOf(sessionId)（有会话多轮记忆）或 "tmp:"+requestId
     * （一次性请求，Redis TTL 过期不跨请求）；sessionId 为 null 表示一次性请求
     * （消息不落 ai_session/ai_message）。</p>
     */
    public record SessionBound(String memoryId, Long sessionId) {

        /**
         * 构造会话绑定
         *
         * @param memoryId  记忆 ID（Agent @MemoryId 参数）
         * @param sessionId 会话 ID（null=一次性请求）
         * @return 绑定实例
         */
        public static SessionBound of(String memoryId, Long sessionId) {
            return new SessionBound(memoryId, sessionId);
        }
    }
}
