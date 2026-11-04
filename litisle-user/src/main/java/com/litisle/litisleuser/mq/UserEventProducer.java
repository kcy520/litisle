package com.litisle.litisleuser.mq;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.user.UserProfileUpdatedEvent;
import com.litisle.litisleapi.event.user.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * user 域 MQ 事件生产统一收口（批次五 B5）。
 *
 * 【职责】把用户注册/资料更新事件统一填充 Schema 基座四字段后发到 EXCHANGE_USER，
 * 供 search 同步 user_idx 索引。
 *
 * 【发送时机】一律在本地写库成功之后调用（本类不做事务感知）：
 * - 注册：user 落库且角色分配完成后发送；
 * - 资料更新：sys_user / user_profile 写库完成后发送。
 *
 * 【失败语义】尽力而为：发送异常仅告警不阻断用户主流程；索引可由事件重放或全量重建兜底，
 * 故不引入 outbox（与 content 域「索引/风控必达」的强语义不同）。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventProducer {

    /** common-mq 统一发送收口（traceId 注入 Header + 失败日志口径统一） */
    private final MqSendUtil mqSendUtil;

    /**
     * 发送 user.registered（用户注册成功事件）。
     *
     * @param event 注册事件（业务字段由调用方填充，本方法只补基座四字段）
     */
    public void sendRegistered(UserRegisteredEvent event) {
        // 基座字段统一填充，禁止调用方自行拼装
        fillBase(event);
        try {
            mqSendUtil.send(MqConstant.EXCHANGE_USER, MqConstant.ROUTING_USER_REGISTERED, event);
            log.info("user.registered 已发布，userId={}", event.getUserId());
        } catch (Exception e) {
            // 发送失败不阻断注册主流程（索引可由后续资料更新或全量重建补齐）
            log.warn("user.registered 发送失败（不阻断注册），userId={}", event.getUserId(), e);
        }
    }

    /**
     * 发送 user.profile-updated（用户资料更新事件）。
     *
     * <p>仅在本次修改涉及 user_idx 索引字段（笔名/简介）时由调用方触发，
     * 用户名/头像/邮箱等非索引字段变更不发，减少无效消息。</p>
     *
     * @param event 资料更新事件（userId 由调用方填充，本方法只补基座四字段）
     */
    public void sendProfileUpdated(UserProfileUpdatedEvent event) {
        // 基座字段统一填充，禁止调用方自行拼装
        fillBase(event);
        try {
            mqSendUtil.send(MqConstant.EXCHANGE_USER, MqConstant.ROUTING_USER_PROFILE_UPDATED, event);
            log.info("user.profile-updated 已发布，userId={}", event.getUserId());
        } catch (Exception e) {
            // 发送失败不阻断资料更新主流程（索引可由全量重建兜底）
            log.warn("user.profile-updated 发送失败（不阻断资料更新），userId={}", event.getUserId(), e);
        }
    }

    /**
     * 填充 UserRegisteredEvent Schema 基座四字段。
     *
     * @param event 注册事件
     */
    private void fillBase(UserRegisteredEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(LocalDateTime.now());
        // traceId 从 MDC 透传（HTTP/Feign 入口由 TraceIdFilter 写入）
        event.setTraceId(MDC.get(HeaderConstant.TRACE_ID));
        event.setVersion(1);
    }

    /**
     * 填充 UserProfileUpdatedEvent Schema 基座四字段。
     *
     * @param event 资料更新事件
     */
    private void fillBase(UserProfileUpdatedEvent event) {
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(LocalDateTime.now());
        // traceId 从 MDC 透传（HTTP/Feign 入口由 TraceIdFilter 写入）
        event.setTraceId(MDC.get(HeaderConstant.TRACE_ID));
        event.setVersion(1);
    }
}
