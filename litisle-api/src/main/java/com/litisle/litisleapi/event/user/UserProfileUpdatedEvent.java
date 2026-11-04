package com.litisle.litisleapi.event.user;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户资料更新事件（MQ 事件 Schema，契约库定义、user 服务发布、search 服务订阅）。
 *
 * 【用途】用户修改对外展示资料（笔名/个人简介）后，search 消费回查权威资料并同步 user_idx，
 * 保证「按笔名/简介检索用户」的结果不过期。
 *
 * 【触发路径】user 侧 UserProfileServiceImpl#updateCurrentUserProfile：
 * 仅在本次修改涉及笔名或简介时发布（用户名/头像/邮箱等非索引字段变更不发，减少无效消息）。
 *
 * 【事件轻量纪律】事件体只带定位键 userId，笔名/简介等字段一律由消费端
 * 经 UserClient#getUserIndexDoc 回查权威值（防事件体被篡改或字段过期）。
 *
 * @author litisle
 */
@Data
public class UserProfileUpdatedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等去重与日志定位） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID（从 MDC 透传，串联 user → MQ → search 全链路日志） */
    private String traceId;

    /** Schema 版本号（当前固定 1，兼容未来字段扩展） */
    private Integer version;

    /** 用户 ID（唯一定位键，消费端据此回查用户公开资料） */
    private Long userId;
}
