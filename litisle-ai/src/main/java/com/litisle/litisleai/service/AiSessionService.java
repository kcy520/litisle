package com.litisle.litisleai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.domain.entity.AiMessage;
import com.litisle.litisleai.domain.entity.AiSession;
import com.litisle.litisleai.enums.AiAgentType;
import com.litisle.litisleai.enums.AiMessageRole;
import com.litisle.litisleai.enums.XiaoyueMode;
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
 * <p>【通俗说明】AI 聊天会话的"户籍管理员"：建会话、查会话、记账消息、删会话都找它。
 * 几条家规——每人最多 200 个暂存会话，超了就自动淘汰最久没聊的那个；一个会话最多
 * 500 条消息，超了就劝你开新会话；删会话采用"软删"：先把会话标记成已删（数据行
 * 留着以备审计），同时把 Redis 记忆、结果映射等联动缓存一并清掉，7 天后定时任务
 * 再彻底物理删除。删除时还会先验明正身——别人的会话一律按"不存在"处理，
 * 连有没有都不会告诉你。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSessionService {

    /** 暂存区标记 */
    private static final int LRU_STASH = 1;

    /** 未删除标记 */
    private static final int DEL_FLAG_NORMAL = 0;
    private static final int DEL_FLAG_DELETED = 1;

    /** 会话标题最大长度（ai_session.title varchar(100)，超长按字符截断） */
    private static final int TITLE_MAX_CHARS = 100;

    /** 会话 Mapper */
    private final AiSessionMapper sessionMapper;

    /** 消息 Mapper */
    private final AiMessageMapper messageMapper;

    /** Agent 配置（治理参数来源） */
    private final AiAgentProperties agentProperties;

    /** Redis 会话记忆存储（删除受理联动清理暂存，板块三 3.2） */
    private final com.litisle.litisleai.memory.RedisChatMemoryStore redisChatMemoryStore;

    /** 会话结果集注册服务（删除受理联动清理序号映射，板块九 1.1） */
    private final SessionResultRegistryService sessionResultRegistry;

    /** 会话条件上下文存储（删除受理联动清理合并基线，板块十 2.1） */
    private final SessionConditionStoreService sessionConditionStore;

    /**
     * 创建会话（暂存区默认）；暂存区达上限时 LRU 淘汰最久未活跃会话（软删懒删）
     *
     * @param userId    用户ID
     * @param agentType Agent 类型（AiAgentType.XIAOYUE）
     * @param mode      小悦模式（XiaoyueMode：POLISH/STRUCTURE/SUMMARY/DEEP，可空）
     * @param title     会话标题（首条消息摘要，可空）
     * @return 新建会话实体（含自增 ID）
     *
     * <p>【通俗说明】开一个新会话：先看看这个用户的暂存区满没满（满了就按 LRU 淘汰
     * 最久没活跃的那个腾位置），然后填好用户、类型、标题（超长截断）等字段落库。</p>
     */
    public AiSession createSession(Long userId, AiAgentType agentType, XiaoyueMode mode, String title) {
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
     *
     * <p>【通俗说明】"这是你的会话吗？"——取会话时验三样：存在、没被删、确实是
     * 本人所有。别人的会话和已删的会话统一报"不存在"，不泄露会话是否存在。</p>
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
     *
     * <p>【通俗说明】列出该用户所有没删的会话，最近聊过的排最前面。</p>
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
     * @param role       角色（AiMessageRole：USER/ASSISTANT）
     * @param content    消息内容
     * @param tokenCnt   token 数（未接估算器传 0）
     * @param promptTokens    该轮输入 token（仅终答行记录，其余传 null）
     * @param completionTokens 该轮输出 token（仅终答行记录，其余传 null）
     * @throws BizException 超过单会话消息上限（10001）
     *
     * <p>【通俗说明】往会话里记一条聊天消息：先数数这个会话已经有多少条了，
     * 到 500 条上限就拒绝并提示开新会话（防止单会话无限膨胀）；没超就把消息
     * 内容、角色、token 数落库（终答行还会额外记进出 token）。</p>
     */
    public void appendMessage(Long sessionId, AiMessageRole role, String content,
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
     *
     * <p>【通俗说明】"摸一下"会话：把最后活跃时间刷新到现在。LRU 淘汰就是看这个
     * 时间决定谁该被清出去——每次聊天都摸一下，经常聊的就不会被误删。</p>
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
     *
     * <p>【通俗说明】删会话四连：① 先验明这是本人会话；② 把会话标记为已删
     * （软删，数据行留着审计）；③ 联动清掉三处关联缓存——Redis 对话记忆、
     * 会话结果序号映射、多轮条件合并基线（防止已删会话的残留数据被后续请求读到）；
     * ④ 打一条审计日志。消息本体不立即删，留给定时任务统一物理清理。</p>
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
        // 会话结果集联动清理（板块九 1.1：防已删会话残留序号映射，TTL 兜底过期）
        sessionResultRegistry.evict(sessionId);
        // 会话条件上下文联动清理（板块十 2.1：防已删会话残留多轮调条件合并基线，TTL 兜底过期）
        sessionConditionStore.evict(sessionId);
        // 审计留痕：软删动作落日志（会话行保留，物理清理前可追溯操作时间线）
        log.info("用户删除会话（软删懒删受理）userId={} sessionId={}", userId, sessionId);
    }

    /**
     * LRU 淘汰（创建前置）：暂存区达上限时按 last_active_time 淘汰最久未活跃会话（软删）
     *
     * @param userId 用户ID
     *
     * <p>【通俗说明】创建会话前的"腾位检查"：数一下这个用户的暂存会话数，没到上限
     * 就直接过；到了就按最后活跃时间找最老的那个，软删掉腾出位置。上限没配置
     * （≤0）就不治理。</p>
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
     *
     * <p>【通俗说明】标题太长就砍到 100 字，防落库时超数据库字段长度报错。</p>
     */
    private String truncateTitle(String title) {
        if (title == null) {
            return null;
        }
        return title.length() <= TITLE_MAX_CHARS ? title : title.substring(0, TITLE_MAX_CHARS);
    }

    /**
     * 会话绑定信息（板块三 3.2：Agent memoryId 与 DB 会话的对应关系载体）
     *
     * <p>memoryId = String.valueOf(sessionId)（有会话多轮记忆）或 "tmp:"+requestId
     * （一次性请求，Redis TTL 过期不跨请求）；sessionId 为 null 表示一次性请求
     * （消息不落 ai_session/ai_message）。</p>
     *
     * <p>【通俗说明】"这次对话挂在哪本记忆册上"的两样凭证：memoryId 是 Agent
     * 记忆的钥匙（正式会话用会话 ID，一次性请求用临时编号、过期自动作废）；
     * sessionId 是数据库会话的编号（null 就表示这是一次不留底的一次性请求）。</p>
     */
    public record SessionBound(String memoryId, Long sessionId) {

        /**
         * 构造会话绑定
         *
         * @param memoryId  记忆 ID（Agent @MemoryId 参数）
         * @param sessionId 会话 ID（null=一次性请求）
         * @return 绑定实例
         *
         * <p>【通俗说明】静态工厂：把 memoryId 和 sessionId 打包成一个绑定对象。</p>
         */
        public static SessionBound of(String memoryId, Long sessionId) {
            return new SessionBound(memoryId, sessionId);
        }
    }
}
