package com.litisle.litisleapi.event.moderation;


import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 处罚结果事件（MQ 事件 Schema，契约库定义、review 服务 5-2.1 发布、auth/user 订阅）
 *
 * 【用途】
 * - auth/user（5-2.2）：消费后经 Feign GET /api/review/punish/status 拉权威态覆盖本地禁言快照；
 *   封禁（action=2）额外执行 Sa-Token kickout 强制下线（5-2.3）
 * - 【事件仅作触发信号】幂等与到达顺序无关：权威态以 Feign 回查覆盖本地为准，
 *   事件字段（含 punishLogId）仅供排障与日志关联，严禁作版本门控/增量判断
 *   （撤销就地改同一 punish_log 行、id 不变不单调，按其丢弃会吞 op=2 撤销事件致误禁持续）
 *
 * 【事件 Schema 公共字段】（对齐 Api-Architecture.md §7.1，全部事件必须有）
 * - eventId / eventTime / traceId / version 语义同 ModerationResultEvent
 *
 * 【业务字段口径】
 * - action：处罚动作（同 punish_log.action：1-禁言 2-封禁 3-内容下架 4-警告）
 * - op：1-生效 / 2-撤销（撤销时 frozenUntil/punishLogId 仍随体携带仅供排障）
 *
 * 【序列化】JSON（Jackson2JsonMessageConverter），本类仅承载字段，不写业务逻辑；
 * 禁止 @Builder（项目 DTO 规范），用 @Data + 手动赋值。
 *
 * @author litisle
 */
@Data
public class PunishResultEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 处罚动作：禁言（同 punish_log.action=1，PunishActionEnum.MUTE） */
    public static final int ACTION_MUTE = 1;

    /** 处罚动作：封禁（同 punish_log.action=2，PunishActionEnum.BAN，消费端据此 kickout） */
    public static final int ACTION_BAN = 2;

    /** 事件唯一 ID（UUID，消费端幂等去重依据之一） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version;

    /** 重试次数（消费端指数退避重投时与消息头双写递增，对账留痕；首投为 null） */
    private Integer retryCount;

    /** 被处罚用户 ID */
    private Long userId;

    /** 处罚动作（1-禁言 2-封禁 3-内容下架 4-警告，口径同 punish_log.action） */
    private Integer action;

    /** 操作类型（1-生效 2-撤销） */
    private Integer op;

    /** 处罚流水 ID（仅排障，严禁作版本门控——撤销就地改同一行、id 不变） */
    private Long punishLogId;

    /** 禁言到期时间（action=1 时有值；封禁永久/撤销时为 null） */
    private LocalDateTime frozenUntil;
}
