package com.litisle.litisleai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.domain.entity.AiMessage;
import com.litisle.litisleai.domain.entity.AiSession;
import com.litisle.litisleai.mapper.AiMessageMapper;
import com.litisle.litisleai.mapper.AiSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 会话治理兜底清扫任务（板块三 3.1：每日 + 启动延迟一次）
 *
 * <p>【清理口径（D25/R9 缺口补齐）】①留存清理：last_active_time 超过留存期（90 天）的
 * 未删会话直接物理删除（行 + 级联消息），防超期留存；②懒删兜底：del_flag=1 且软删超过
 * 延迟期（7 天）的会话物理删除，防用户删除请求后数据残留。物理删除前会话行保留即审计
 * 留痕（删除动作时间线由 update_time 承载）。</p>
 *
 * 
 * <p>【调度】每日 03:30 低峰执行 + 启动 60 秒后补跑一次（fixedDelay 极大值只触发一次）。
 * 单轮清理失败不中断服务（下轮重试自愈）。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSessionCleanupTask {

// 扫描`ai_session` 表，按两类口径筛出需要 物理删除 的会话：
// del_flag=0 且 last_active_time < now - 90天（留存期可配）			防“僵尸会话”超期留存，控制存储成本与合规风险
// del_flag=1 且 update_time < now - 7天（延迟期可配）	                用户点了删除只是软删（打标记），这个任务兜底把残留数据真正清掉
// 1. 为什么需要“懒删”？ 用户删除会话是软删（`del_flag=1` ），立即物理删除会有风险（误删不可恢复、级联消息删除耗时长）。所以设计成“先软删 → 延迟 7 天 → 定时任务物理删除”，给恢复留窗口。
// 2. 级联删除 ：每个会话删之前先删`ai_message` 里该会话的所有消息（ purgeBatch ），防止产生“孤儿消息”——消息还在但会话没了，无法归属。
// 3. 分批处理 ：每批 200 条，用分页插件取批（不拼 LIMIT 字符串），防大事务拖垮数据库。
// 4. 容错语义 ：单条删除失败只跳过该会话（下轮重试自愈），整轮失败也只记日志， 不影响服务运行 。
// 5. 调度 ：每日凌晨 03:30 低峰执行 + 应用启动 60 秒后补跑一次（`fixedDelay = Long.MAX_VALUE` 这个技巧让任务只触发一次）。

    /** 单批物理清理的会话数（防大事务与长查询） */
    private static final int BATCH_SIZE = 200;

    /** 会话 Mapper */
    private final AiSessionMapper sessionMapper;

    /** 消息 Mapper（级联物理删除） */
    private final AiMessageMapper messageMapper;

    /** Agent 配置（留存期/懒删延迟参数来源） */
    private final AiAgentProperties agentProperties;

    /**
     * 定时清扫入口：每日 03:30 + 启动 60 秒补跑一次
     */
    @Scheduled(cron = "0 30 3 * * ?")
    @Scheduled(initialDelay = 60_000L, fixedDelay = Long.MAX_VALUE)
    public void cleanup() {
        try {
            // ① 留存清理：超过留存期的未删会话物理删除（行 + 消息）
            purgeExpiredSessions();
            // ② 懒删兜底：软删超延迟期的会话物理删除（行 + 消息）
            purgeSoftDeletedSessions();
        } catch (Exception e) {
            // 清扫失败不中断服务（下轮重试自愈），仅记录错误
            log.error("AI 会话治理兜底清扫失败", e);
        }
    }

    /**
     * 留存清理：last_active_time 早于（now - 90 天）的未删会话分批物理删除
     */
    private void purgeExpiredSessions() {
        LocalDateTime expireBefore = LocalDateTime.now().minusDays(agentProperties.getSessionRetentionDays());
        int purged = purgeBatch(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getDelFlag, 0)
                .lt(AiSession::getLastActiveTime, expireBefore)
                .orderByAsc(AiSession::getLastActiveTime));
        if (purged > 0) {
            log.info("会话留存期清理完成 retentionDays={} purged={}", agentProperties.getSessionRetentionDays(), purged);
        }
    }

    /**
     * 懒删兜底：软删时间超过延迟期的会话分批物理删除
     */
    private void purgeSoftDeletedSessions() {
        LocalDateTime deletedBefore = LocalDateTime.now().minusDays(agentProperties.getSessionPurgeDelayDays());
        int purged = purgeBatch(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getDelFlag, 1)
                .lt(AiSession::getUpdateTime, deletedBefore)
                .orderByAsc(AiSession::getUpdateTime));
        if (purged > 0) {
            log.info("软删会话物理清理完成 purgeDelayDays={} purged={}", agentProperties.getSessionPurgeDelayDays(), purged);
        }
    }

    /**
     * 按条件分批物理删除会话及其级联消息（单批 BATCH_SIZE，防大事务）
     *
     * @param wrapper 会话筛选条件（调用方构造：留存超期或软删超期）
     * @return 本轮实际物理删除的会话数
     */
    private int purgeBatch(LambdaQueryWrapper<AiSession> wrapper) {
        int purged = 0;
        while (true) {
            // 分页插件取一批（不拼 LIMIT 串）
            List<AiSession> batch = sessionMapper.selectPage(
                    new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, BATCH_SIZE), wrapper).getRecords();
            if (batch.isEmpty()) {
                break;
            }
            for (AiSession session : batch) {
                // 先删级联消息再删会话行（删消息失败跳过该会话下轮重试，防孤儿消息无法归属）
                try {
                    messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                            .eq(AiMessage::getSessionId, session.getId()));
                    sessionMapper.deleteById(session.getId());
                    purged++;
                } catch (Exception e) {
                    log.warn("会话物理删除失败（下轮重试）sessionId={}", session.getId(), e);
                }
            }
            // 批量不足一页说明已扫完
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }
        return purged;
    }
}
