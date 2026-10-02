package com.litisle.litisleapi.event.user;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户注册事件（MQ 事件 Schema，契约库定义、user 服务发布、下游服务订阅）
 *
 * 【用途】
 * - notification：发送欢迎站内信
 * - search：初始化用户 ES 索引（后续扩展）
 *
 * 【实现步骤】（对齐 Api-Architecture.md §7.1 事件 Schema 规范）
 * 1. 实现 Serializable，字段如下（前 4 个为事件 Schema 公共字段，全部事件必须有）：
 *    - String  eventId        事件唯一 ID（UUID，用于消费端幂等去重）
 *    - LocalDateTime eventTime 事件发生时间
 *    - String  traceId        链路追踪 ID（从 SecurityContext/上下文透传）
 *    - Integer version        Schema 版本号（预留，当前固定 1，兼容未来字段变更）
 * 2. 追加业务字段：
 *    - Long    userId          用户 ID
 *    - String  username        用户名
 *    - String  uid             对外 UID
 *    - String  registerSource  注册来源（复用 {@link com.litisle.litisleapi.enums.RegisterSourceEnum} 的 code）
 * 3. 序列化方式为 JSON（发送时用 Jackson 转字节，消费端按类反序列化），本类仅承载字段，不写业务逻辑；
 *    禁止 @Builder（项目 DTO 规范），用 @Data + 手动赋值或链式 setter。
 * 4. 发布时机（litisle-user 服务，UserServiceImpl 注册方法末尾）：
 *    - 必须在 createUser 事务提交后发布，避免消费方读到未提交数据：
 *      用 Spring 事务事件 @TransactionalEventListener(phase = AFTER_COMMIT) 监听注册成功信号，
 *      在监听器内组装本事件并 rabbitTemplate.convertAndSend(MqConstant.EXCHANGE_USER,
 *      MqConstant.ROUTING_USER_REGISTERED, event)
 * 5. MQ 绑定（常量已在 MqConstant 定义，无需新增）：
 *    Exchange = litisle.user.exchange，Routing Key = user.registered，
 *    Queue = litisle.user.registered.queue（notification 服务声明并订阅）
 * 6. 消费端约定：
 *    - 用 eventId 做幂等（Redis SETNX 或唯一索引，重复投递直接 ACK 丢弃）
 *    - 消费失败重试耗尽后入死信队列，不阻塞主流程
 */
public class UserRegisteredEvent {

}
