package com.litisle.litisleapi.event.user;


import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户封禁事件（MQ 事件 Schema，契约库定义、user 服务发布、content 等下游服务订阅）
 *
 * 【用途】
 * - content：封禁连带下架（板块八 8.2）——该作者全部已发布文章批量置 4-下架，
 *   逐篇发 content.unpublished（search 移除 ES 索引 / ai 移除向量库 chunk），
 *   审核缓存键失效由 content 侧本地执行（不走本事件，口径同 ContentUnpublishedEvent 铁律）
 *
 * 【实现步骤】（对齐 Api-Architecture.md §7.1 事件 Schema 规范）
 * 1. 实现 Serializable，前 4 个为事件 Schema 公共基座字段（全部事件必须有）：
 *    - String  eventId         事件唯一 ID（UUID，消费端幂等去重）
 *    - LocalDateTime eventTime 事件发生时间
 *    - String  traceId         链路追踪 ID（从 MDC 透传）
 *    - Integer version         Schema 版本号（当前固定 1）
 * 2. 追加业务字段：
 *    - Long    userId          被封禁用户 ID
 *    - String  reason          封禁原因（下游审计留痕用，可空）
 * 3. 序列化 JSON，禁止 @Builder（项目 DTO 规范），@Data + 手动赋值；
 * 4. 发布时机（litisle-user，封禁事务提交后 AFTER_COMMIT）：
 *    Exchange=MqConstant.EXCHANGE_USER，Routing=MqConstant.ROUTING_USER_BANNED；
 * 5. 消费端约定：连带下架为幂等 CAS（重复消费安全）；消费失败重试耗尽入死信。
 */
@Data
public class UserBannedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等去重） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID（从上下文透传） */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version;

    /** 被封禁用户 ID（content 侧按 ownerId 连带下架其全部已发布文章） */
    private Long userId;

    /** 封禁原因（下游审计留痕用，可空） */
    private String reason;
}
