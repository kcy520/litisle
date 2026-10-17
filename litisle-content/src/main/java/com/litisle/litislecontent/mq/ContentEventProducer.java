package com.litisle.litislecontent.mq;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.content.ContentUnpublishedEvent;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
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
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentEventProducer {

    /** common-mq 统一发送收口（traceId 注入 Header + 失败日志口径统一） */
    private final MqSendUtil mqSendUtil;

    /**
     * 发送 content.unpublished（下线四路径统一收口：删除/转私密/复审驳回/封禁连带）
     * <p>消费方：search（移除 ES 索引）/ ai（移除向量库 chunk，防下架内容继续被 RAG 检索——隐私红线）</p>
     * <p>失败语义：尽力而为（send 仅 error 留痕）；缓存键本地失效由调用方同步执行
     * ContentUnpublishedInvalidator，不经本事件（两者职责不可互替）</p>
     *
     * @param event 下架事件（bizType/bizId/contentHash/reason 由调用方填充）
     */
    public void sendUnpublished(ContentUnpublishedEvent event) {
        fillBase(event);
        mqSendUtil.send(MqConstant.EXCHANGE_CONTENT, MqConstant.ROUTING_CONTENT_UNPUBLISHED, event);
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
        mqSendUtil.send(MqConstant.EXCHANGE_MODERATION, MqConstant.ROUTING_MODERATION_TEXT_SUBMIT, event);
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
        mqSendUtil.send(MqConstant.EXCHANGE_MODERATION, MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT, event);
        log.info("image.submit 已发布，bizType={}，bizId={}，scene={}",
                event.getBizType(), event.getBizId(), event.getScene());
    }

    // TODO(板块四 [4.2])：补 sendPublished（ContentPublishedEvent 契约类为缺口，随转公开链路一并补）
    //  落地后本类即为 content 域唯一事件出口

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
     * 填充 ImageSubmitEvent Schema 基座四字段（重载：三事件无公共基类，禁各自拼装基座字段）
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
}