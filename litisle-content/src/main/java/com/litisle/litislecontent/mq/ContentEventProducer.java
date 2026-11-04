package com.litisle.litislecontent.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.content.ContentArticleUpdatedEvent;
import com.litisle.litisleapi.event.content.ContentPublishedEvent;
import com.litisle.litisleapi.event.content.ContentUnpublishedEvent;
import com.litisle.litisleapi.event.content.ContentViewCountFlushedEvent;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
import com.litisle.litislecontent.domain.entity.EventOutbox;
import com.litisle.litislecontent.mapper.EventOutboxMapper;
import com.litisle.litislecontent.service.support.FeedHotRankSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * content 域 MQ 事件生产统一收口（板块一 [1.2] 软删/转私密红线；板块三 [3.1] submitted / [3.4] 扩全）
 *
 * ContentEventProducer 是 content 域事件的 MQ 发送收口类，负责把“文章下线/下架”等事件统一包装、
 * 填充标准字段、通过 MqSendUtil 发到消息队列，并且强制要求必须在本地事务提交后才能发送。
 *
 * <p>事件 Schema 基座四字段（eventId/eventTime/traceId/version）统一 {@link #fillBase} 填充，
 * 禁各方法自行拼装（对齐 Api-Architecture §7.1 事件 Schema 规范）</p>
 * <p>发送时机铁律：一律由调用方在本地事务提交后（afterCommit）调用，禁事务内发送（回滚后事件已飞）</p>
 * <p>本地 outbox 兜底（批次3 2026-11-02）：每个事件发送前先落 event_outbox（status=0 待发送），
 * 发送成功置 1、失败置 2，EventOutboxResendJob 定时扫描重发——兜住「发送前进程崩溃」「发送失败」
 * 两类事件丢失（search 索引/ai RAG 依赖事件必达）；消费端按 eventId 幂等去重，重发无副作用</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentEventProducer {

    /** common-mq 统一发送收口（traceId 注入 Header + 失败日志口径统一） */
    private final MqSendUtil mqSendUtil;

    /** 事件发件箱 Mapper（本地 outbox：发送前落表 + 发送结果回写） */
    private final EventOutboxMapper eventOutboxMapper;

    /** JSON 序列化（Spring Boot 全局 ObjectMapper，含 JavaTimeModule，与 MQ 转换器口径兼容） */
    private final ObjectMapper objectMapper;

    /** 热度榜支撑（下线时实时剔除 ZSet 榜成员，批次E P2 定案 D-1 2026-11-03） */
    private final FeedHotRankSupport feedHotRankSupport;

    /**
     * 发送 content.unpublished（下线四路径统一收口：删除/转私密/复审驳回/封禁连带）
     * <p>消费方：search（移除 ES 索引）/ ai（移除向量库 chunk，防下架内容继续被 RAG 检索——隐私红线）</p>
     * <p>失败语义：outbox 必达兜底——发送失败落 outbox 失败态由重发 job 补发，不向调用方抛异常
     * （缓存键本地失效由调用方同步执行 ContentUnpublishedInvalidator，不经本事件，两者职责不可互替）</p>
     * <p>热度榜实时剔除（批次E P2 定案 D-1）：增量灌榜只升不降、靠时间列捞不到「成员消失」，
     * 不显式 ZREM 则下架文章会在榜上残留最长一个全量轮周期（默认 1h）——本方法是四路径统一收口，
     * 各路径无需感知榜单存在；removeFromRank 内部 fail-safe 吞异常，不影响事件发送主流程</p>
     *
     * @param event 下架事件（bizType/bizId/contentHash/reason 由调用方填充；热榜只收文章事件）
     */
    public void sendUnpublished(ContentUnpublishedEvent event) {
        fillBase(event);
        // 热度榜实时剔除（仅文章事件；ZREM 成员不存在时无副作用，Redis 异常内部吞掉走兜底）
        if (event.getBizType() != null && ModerationConstant.BIZ_TYPE_ARTICLE == event.getBizType()) {
            feedHotRankSupport.removeFromRank(event.getBizId());
        }
        dispatch(MqConstant.EXCHANGE_CONTENT, MqConstant.ROUTING_CONTENT_UNPUBLISHED, event, event.getEventId());
        log.info("content.unpublished 已发布，bizId={}，reason={}", event.getBizId(), event.getReason());
    }

    /**
     * 发送 content.submitted（文本送审事件，路由至 moderation.exchange/moderation.text.submit）
     * <p>发布同步段 A3（DFA 闸门放行 + last-hash 未命中免审）后异步送审；事件体不含正文，
     * review 消费端按 bizType+bizId+scene 定位任务后经 ContentClient 回查正文（切片/聚合均在消费端）</p>
     * <p>业务字段（bizType/bizId/scene/contentHash/modType/retryCount）由调用方填充，本方法仅补基座四字段</p>
     *
     * @param event 文本送审事件
     */
    public void sendSubmitted(TextSubmitEvent event) {
        fillBase(event);
        dispatch(MqConstant.EXCHANGE_MODERATION, MqConstant.ROUTING_MODERATION_TEXT_SUBMIT, event, event.getEventId());
        log.info("content.submitted 已发布，bizType={}，bizId={}，scene={}",
                event.getBizType(), event.getBizId(), event.getScene());
    }

    /**
     * 发送 image.submit（配图发布复审事件，路由至 moderation.exchange/moderation.image.submit）
     * <p>发布同步段 A3（含配图文章照常送审）后异步复审；事件体不含图片地址，
     * review 消费端按 bizType+bizId+scene 定位文章级复审任务后经 FileClient 取图逐张送审，
     * contentHash=全部配图 sha256 排序拼接后再 sha256 的聚合指纹（与免审连带校验同口径）</p>
     * <p>业务字段（bizType/bizId/scene/contentHash/modType/retryCount）由调用方填充，本方法仅补基座四字段</p>
     *
     * @param event 配图复审送审事件
     */
    public void sendImageSubmit(ImageSubmitEvent event) {
        fillBase(event);
        dispatch(MqConstant.EXCHANGE_MODERATION, MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT, event, event.getEventId());
        log.info("image.submit 已发布，bizType={}，bizId={}，scene={}",
                event.getBizType(), event.getBizId(), event.getScene());
    }

    /**
     * 发送 content.published（发布成功/转公开不重审事件）
     * <p>消费方：search（建立/恢复 ES 索引）/ ai（RAG 重新收录，门控回查 status=2 且 visibility=公开）/
     * notification（发布结果通知）</p>
     * <p>转公开场景不重审（[4.1]：方案B 禁改正文 + content_hash 未变，原审核结论仍有效），
     * 且不重建/不重写任何审核缓存（原键仍有效，直落命中口径不变）</p>
     * <p>业务字段（bizType/bizId/contentHash）由调用方填充，本方法仅补基座四字段</p>
     *
     * @param event 发布成功事件
     */
    public void sendPublished(ContentPublishedEvent event) {
        fillBase(event);
        dispatch(MqConstant.EXCHANGE_CONTENT, MqConstant.ROUTING_CONTENT_PUBLISHED, event, event.getEventId());
        log.info("content.published 已发布，bizId={}", event.getBizId());
    }

    /**
     * 发送 content.article-updated（已发布文章元数据编辑，批次A A2 定案 / 批次B B7 发布点接入）
     * <p>消费方：search（轻量回查元数据后 partial update article_idx 的 title/tags/summary 等索引字段，
     * 不动向量与 chunks）；不发事件则已发布文章改元数据后索引字段将永久过期</p>
     * <p>调用时机铁律：业务事务提交后（afterCommit）调用，防回滚后产生脏事件；
     * 事件体只带定位键（bizType/bizId/contentHash），消费端自行回查权威值</p>
     *
     * @param event 元数据编辑事件（bizType/bizId/contentHash 由调用方填充）
     */
    public void sendArticleUpdated(ContentArticleUpdatedEvent event) {
        fillBase(event);
        dispatch(MqConstant.EXCHANGE_CONTENT, MqConstant.ROUTING_CONTENT_ARTICLE_UPDATED, event, event.getEventId());
        log.info("content.article-updated 已发布，bizId={}", event.getBizId());
    }

    /**
     * 发送 content.view-count-flushed（浏览量落库后补推，批次A A5/A7 定案 / 批次B B7 发布点接入）
     * <p>消费方：search（覆盖写 Redis 热度待刷新键的 view_cnt 绝对值，再由 IndexHeatFlushJob 定时批量
     * 重算 heat，不直写 ES）</p>
     * <p>调用时机：ViewCountFlushJob 刷回 MySQL 成功后直接发送——定时任务无事务包裹，落库即提交，
     * 无需 afterCommit 钩子；viewCount 必须是落库后的最新绝对值（覆盖语义，禁传增量）</p>
     *
     * @param event 浏览量落库事件（bizType/bizId/viewCount 由调用方填充）
     */
    public void sendViewCountFlushed(ContentViewCountFlushedEvent event) {
        fillBase(event);
        dispatch(MqConstant.EXCHANGE_CONTENT, MqConstant.ROUTING_CONTENT_VIEW_COUNT_FLUSHED, event, event.getEventId());
        log.debug("content.view-count-flushed 已发布，bizId={}，viewCount={}",
                event.getBizId(), event.getViewCount());
    }

    /**
     * 统一发送编排：先落发件箱（待发送）→ 必达语义发送 → 按结果回写发件箱状态。
     *
     * <p>为什么先落表再发送：若先发送后落表，进程在两步之间崩溃则事件既已飞出又无留痕，
     * 「发送前落表」保证任何时点崩溃都有重发兜底（最坏情况=重发重复消息，消费端 eventId 幂等消化）</p>
     * <p>失败语义：全程不向调用方抛异常（保持既有「不阻断主流程」口径），发送失败落发件箱失败态
     * 由 EventOutboxResendJob 补发；发件箱自身写失败也只 warn（退化为「尽力而为」现状，不阻断业务）</p>
     *
     * @param exchange  目标交换机
     * @param routingKey 路由键
     * @param event     事件体（基座字段已填充）
     * @param eventId   事件唯一ID（事件体无公共基类，由各 send 方法显式传入）
     */
    private void dispatch(String exchange, String routingKey, Object event, String eventId) {
        // 步骤1：发送前落发件箱（status=0 待发送），拿到主键供结果回写
        EventOutbox outbox = recordOutbox(exchange, routingKey, event, eventId);
        try {
            // 步骤2：必达语义发送（内部静默重试 1 次，仍失败抛 BizException）
            mqSendUtil.sendCritical(exchange, routingKey, event);
            // 步骤3a：发送成功 → 回写已发送 + 成功时间；回写失败仅 warn（行停留待发送态，
            // 重发 job 会再发一次，消费端 eventId 幂等去重无副作用）
            markOutbox(outbox, EventOutbox.STATUS_SENT, null);
        } catch (Exception e) {
            // 步骤3b：发送失败 → 回写失败态，重发 job 兜底补发；不抛出（不阻断主流程）
            log.error("MQ 事件发送失败（已落发件箱待重发），exchange={}，routingKey={}，eventId={}",
                    exchange, routingKey, eventId, e);
            markOutbox(outbox, EventOutbox.STATUS_FAILED, e);
        }
    }

    /**
     * 事件发送前落发件箱表（status=0 待发送）。
     *
     * @param exchange  目标交换机
     * @param routingKey 路由键
     * @param event     事件体
     * @param eventId   事件唯一ID
     * @return 落表成功返回实体（含回填主键）；落表失败返回 null（调用方跳过结果回写，仅发送）
     */
    private EventOutbox recordOutbox(String exchange, String routingKey, Object event, String eventId) {
        try {
            // 组装发件箱行：事件快照 JSON + 收发地址 + 待发送初始态
            EventOutbox outbox = new EventOutbox()
                    .setEventId(eventId)                                              // 与事件体 eventId 同值
                    .setEventType(event.getClass().getSimpleName())                    // 事件类型（排查定位用）
                    .setPayloadType(event.getClass().getName())                        // 类全名（重发反序列化依据）
                    .setExchange(exchange)                                             // 目标交换机
                    .setRoutingKey(routingKey)                                         // 路由键
                    .setPayload(objectMapper.writeValueAsString(event))                // 事件体 JSON 快照
                    .setStatus(EventOutbox.STATUS_PENDING)                             // 初始：待发送
                    .setRetryCount(0);                                                 // 重发次数从 0 计
            eventOutboxMapper.insert(outbox);
            return outbox;
        } catch (Exception e) {
            // 发件箱落表失败不阻断发送（退化为「尽力而为」现状），warn 留痕供排查
            log.warn("事件发件箱落表失败（不阻断发送，本条事件退化为尽力而为），eventType={}",
                    event.getClass().getSimpleName(), e);
            return null;
        }
    }

    /**
     * 按发送结果回写发件箱状态（outbox 为 null 表示落表已失败，直接跳过）。
     *
     * @param outbox    发件箱行（含主键）
     * @param status    目标状态：1-已发送 / 2-发送失败
     * @param cause     发送失败原因（成功时为 null，仅日志用）
     */
    private void markOutbox(EventOutbox outbox, int status, Exception cause) {
        // 落表已失败的行无主键可回写，直接跳过（事件本身已尽力发送）
        if (outbox == null) {
            return;
        }
        try {
            EventOutbox update = new EventOutbox();
            update.setId(outbox.getId());
            update.setStatus(status);
            if (status == EventOutbox.STATUS_SENT) {
                // 成功态额外记录成功发送时间（清理策略按此判过期）
                update.setSendTime(LocalDateTime.now());
            }
            eventOutboxMapper.updateById(update);
        } catch (Exception e) {
            // 状态回写失败仅 warn：行停留待发送态，重发 job 幂等补发（消费端 eventId 去重无副作用）
            log.warn("事件发件箱状态回写失败（outboxId={}，目标状态={}），重发 job 兜底",
                    outbox.getId(), status, cause != null ? cause : e);
        }
    }

    /**
     * 填充事件 Schema 基座四字段（全部事件必须，禁遗漏）
     *
     * @param event 事件体（实现 Serializable 的契约事件）
     */
    private void fillBase(ContentUnpublishedEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(LocalDateTime.now());
        // traceId 从 MDC 透传（HTTP 入口由 TraceIdFilter 生成；定时任务场景为 null 属正常）
        event.setTraceId(MDC.get(HeaderConstant.TRACE_ID));
        event.setVersion(1);
    }

    /**
     * 填充 TextSubmitEvent Schema 基座四字段（重载：两事件无公共基类，禁各自拼装基座字段）
     *
     * @param event 文本送审事件
     */
    private void fillBase(TextSubmitEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(LocalDateTime.now());
        // traceId 从 MDC 透传（HTTP 入口由 TraceIdFilter 生成；定时任务场景为 null 属正常）
        event.setTraceId(MDC.get(HeaderConstant.TRACE_ID));
        event.setVersion(1);
    }

    /**
     * 填充 ImageSubmitEvent Schema 基座四字段（重载：四事件无公共基类，禁各自拼装基座字段）
     *
     * @param event 配图复审送审事件
     */
    private void fillBase(ImageSubmitEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(LocalDateTime.now());
        // traceId 从 MDC 透传（HTTP 入口由 TraceIdFilter 生成；定时任务场景为 null 属正常）
        event.setTraceId(MDC.get(HeaderConstant.TRACE_ID));
        event.setVersion(1);
    }

    /**
     * 填充 ContentPublishedEvent Schema 基座四字段（重载：四事件无公共基类，禁各自拼装基座字段）
     *
     * @param event 发布成功事件
     */
    private void fillBase(ContentPublishedEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(LocalDateTime.now());
        // traceId 从 MDC 透传（HTTP 入口由 TraceIdFilter 生成；定时任务场景为 null 属正常）
        event.setTraceId(MDC.get(HeaderConstant.TRACE_ID));
        event.setVersion(1);
    }

    /**
     * 填充 ContentArticleUpdatedEvent Schema 基座四字段（重载：事件体无公共基类，禁各自拼装基座字段）
     *
     * @param event 元数据编辑事件
     */
    private void fillBase(ContentArticleUpdatedEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(LocalDateTime.now());
        // traceId 从 MDC 透传（HTTP 入口由 TraceIdFilter 生成）
        event.setTraceId(MDC.get(HeaderConstant.TRACE_ID));
        event.setVersion(1);
    }

    /**
     * 填充 ContentViewCountFlushedEvent Schema 基座四字段（重载：事件体无公共基类，禁各自拼装基座字段）
     *
     * @param event 浏览量落库事件
     */
    private void fillBase(ContentViewCountFlushedEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(LocalDateTime.now());
        // traceId 从 MDC 透传（定时任务场景通常为 null 属正常）
        event.setTraceId(MDC.get(HeaderConstant.TRACE_ID));
        event.setVersion(1);
    }
}
