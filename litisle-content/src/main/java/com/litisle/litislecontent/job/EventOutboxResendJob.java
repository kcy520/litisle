package com.litisle.litislecontent.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.litislecontent.domain.entity.EventOutbox;
import com.litisle.litislecontent.mapper.EventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 事件发件箱重发定时任务（批次3 2026-11-02，MQ 事件必达兜底）。
 *
 * 作用：
 * 按配置周期（默认 1min）扫描 event_outbox 表中「待发送/发送失败」且重发次数未达上限的事件行，
 * 反序列化事件快照后经 MqSendUtil 必达语义补发——兜住生产者「发送前进程崩溃」「发送失败」
 * 两类事件丢失（search 索引/ai RAG 依赖 content.published/unpublished 必达）。
 * 顺带清理已发送超保留期的发件箱行（默认 7 天），防表无界增长。
 * 范式对齐 FeedHotRankRefillJob：@Scheduled + enabled 开关 + 分布式锁单点 + fail-safe 吞异常；
 * 消费端按 eventId 幂等去重，重发重复消息无副作用。
 *
 * @author litisle
 */
@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class EventOutboxResendJob {

    /** 发件箱 Mapper（重发扫描 + 状态回写 + 已发送行清理） */
    private final EventOutboxMapper eventOutboxMapper;

    /** common-mq 必达发送收口（补发与首发同一发送链路，traceId/Header 口径一致） */
    private final MqSendUtil mqSendUtil;

    /** JSON 反序列化（Spring Boot 全局 ObjectMapper，与落表序列化同一实例口径） */
    private final ObjectMapper objectMapper;

    /** 分布式锁（多实例单点重发，防多实例同时捞同一批行重复补发） */
    private final DistributedLock distributedLock;

    /** 重发锁键：多实例误开开关时防并发重发互踩 */
    private static final String RESEND_LOCK_KEY = CacheConstant.LOCK_PREFIX + "content:event:outbox:resend";

    /** 重发开关（默认开；多实例部署时分布式锁保证单点执行，也可只开一台再关其余） */
    @Value("${litisle.content.outbox.resend-enabled:true}")
    private boolean enabled;

    /** 重发次数上限（单行超过后置 status=3 放弃留痕，error 告警人工介入） */
    @Value("${litisle.content.outbox.max-retry-count:5}")
    private int maxRetryCount;

    /** 单轮重发批量上限（fixedDelay 模式下超限部分下一轮自然消化，防单轮拖太久） */
    @Value("${litisle.content.outbox.resend-batch-size:200}")
    private int resendBatchSize;

    /** 已发送行保留天数（发送成功满 N 天后清理，防表无界增长；默认 7 天） */
    @Value("${litisle.content.outbox.retain-days:7}")
    private int retainDays;

    /**
     * 定时重发 + 清理。
     *
     * 作用：按配置周期执行单轮重发（扫待发送/失败行逐条补发）与已发送行过期清理；
     * 受 outbox.resend-enabled 开关控制；抢不到锁（他实例在跑）直接放弃本轮；
     * 单行失败不影响批内其他行（逐行 try-catch，fail-safe）。
     */
    @Scheduled(fixedDelayString = "${litisle.content.outbox.resend-interval-ms:60000}",
            initialDelayString = "${litisle.content.outbox.initial-delay-ms:60000}")
    public void resend() {
        // 步骤1：开关判断
        if (!enabled) {
            return;
        }
        // 步骤2：抢重发锁（wait=0 不等待：抢不到说明他实例本轮已在跑，直接放弃，fixedDelay 不堆积）
        if (!distributedLock.tryLock(RESEND_LOCK_KEY, 0, TimeUnit.SECONDS)) {
            return;
        }
        try {
            // 步骤3：重发本批（核心职责）
            resendBatch();
            // 步骤4：清理已发送过期行（附带职责，DELETE 走 idx_sent_update 开销可忽略）
            cleanSent();
        } catch (Exception e) {
            // 兜底 fail-safe：本轮异常不影响下轮
            log.warn("事件发件箱重发任务异常（fail-safe 下轮重试）", e);
        } finally {
            distributedLock.unlock(RESEND_LOCK_KEY);
        }
    }

    /**
     * 单轮重发：扫描「待发送/发送失败」且未达重发上限的事件行，逐条反序列化补发并回写结果。
     */
    private void resendBatch() {
        // 步骤1：捞本批候选行（status=0 待发送 / 2 发送失败；retry_count < 上限；按创建时间先进先出）
        LambdaQueryWrapper<EventOutbox> query = new LambdaQueryWrapper<EventOutbox>()
                .in(EventOutbox::getStatus, EventOutbox.STATUS_PENDING, EventOutbox.STATUS_FAILED)
                .lt(EventOutbox::getRetryCount, maxRetryCount)
                .orderByAsc(EventOutbox::getCreateTime);
        // 用 Page 替代 .last("LIMIT")：分页拦截器标准方式，无裸 SQL 字符串；
        // searchCount=false 跳过 COUNT 查询（口径对齐 DraftServiceImpl#locateDraft）
        List<EventOutbox> candidates = eventOutboxMapper.selectList(
                Page.of(1, resendBatchSize, false), query);
        // 空轮静默返回（常态：绝大多数事件首发即成功，发件箱无积压）
        if (candidates.isEmpty()) {
            return;
        }
        // 步骤2：逐条补发（单行失败仅回写该行，不影响批内其他行）
        int sent = 0;
        for (EventOutbox row : candidates) {
            if (resendOne(row)) {
                sent++;
            }
        }
        // 步骤3：结果留痕（本轮有补发动作才记日志）
        log.info("事件发件箱重发完成：candidate={}，sent={}", candidates.size(), sent);
    }
    

    /**
     * 重发单条事件：反序列化事件快照 → 必达发送 → 回写发件箱状态。
     *
     * @param row 发件箱行
     * @return true=补发成功；false=仍失败（已按次数升级为失败态或放弃态）
     */
    private boolean resendOne(EventOutbox row) {
        try {
            // 步骤1：按 payloadType 还原事件对象（schema 漂移反序列化失败按发送失败计次）
            Class<?> eventType = Class.forName(row.getPayloadType());
            Object event = objectMapper.readValue(row.getPayload(), eventType);
            // 步骤2：必达语义补发（eventId 未变，消费端幂等去重无副作用）
            mqSendUtil.sendCritical(row.getExchange(), row.getRoutingKey(), event);
            // 步骤3a：成功 → 置已发送 + 成功时间
            EventOutbox update = new EventOutbox();
            update.setId(row.getId());
            update.setStatus(EventOutbox.STATUS_SENT);
            update.setSendTime(LocalDateTime.now());
            eventOutboxMapper.updateById(update);
            return true;
        } catch (Exception e) {
            // 步骤3b：失败 → 重发次数 +1；达到上限置放弃态（死信留痕）并 error 告警人工介入
            int nextRetry = row.getRetryCount() + 1;
            EventOutbox update = new EventOutbox();
            update.setId(row.getId());
            update.setRetryCount(nextRetry);
            update.setStatus(nextRetry >= maxRetryCount ? EventOutbox.STATUS_ABANDONED : EventOutbox.STATUS_FAILED);
            eventOutboxMapper.updateById(update);
            log.error("事件发件箱补发失败（outboxId={}，eventId={}，eventType={}，retryCount={}/{}，status={}",
                    row.getId(), row.getEventId(), row.getEventType(), nextRetry, maxRetryCount,
                    nextRetry >= maxRetryCount ? "放弃留痕需人工介入" : "继续待重发", e);
            return false;
        }
    }

    /**
     * 清理已发送过期行：发送成功满保留期（默认 7 天）的行物理删除，防表无界增长。
     */
    private void cleanSent() {
        // 计算保留期截止时间：update_time 早于该时刻的已发送行视为过期
        LocalDateTime expireBefore = LocalDateTime.now().minusDays(retainDays);
        int deleted = eventOutboxMapper.delete(new LambdaQueryWrapper<EventOutbox>()
                .eq(EventOutbox::getStatus, EventOutbox.STATUS_SENT)
                .lt(EventOutbox::getUpdateTime, expireBefore));
        // 有清理动作才记日志（正常轮次静默）
        if (deleted > 0) {
            log.info("事件发件箱已发送过期行清理完成：deleted={}，retainDays={}", deleted, retainDays);
        }
    }
}
