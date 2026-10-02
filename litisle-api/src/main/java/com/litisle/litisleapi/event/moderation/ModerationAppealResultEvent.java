package com.litisle.litisleapi.event.moderation;



import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 申诉裁决结果事件（MQ 事件 Schema，契约库定义、review 服务发布、下游 file 等订阅）
 *
 * 【用途】
 * - file：改判 PASS 时将违规图从 quarantine/ 移回原前缀 + safe_flag 回 1（unquarantine 幂等）；
 *   维持驳回时 file 侧无操作（保持隔离）
 *
 * 【事件 Schema 公共字段】（对齐 Api-Architecture.md §7.1）
 * - eventId / eventTime / traceId / version，语义同 ModerationResultEvent
 *
 * 【业务字段】
 * - bizType / bizId  同 ModerationResultEvent（图片时 bizId=fileId）
 * - appealId         申诉单ID
 * - appealPass       裁决结果：true=改判PASS（移回原前缀）；false=维持驳回（保持隔离）
 * - reviewTaskId     关联审核任务ID（改判事件体必带，对齐设计文档定稿口径）
 *
 * 【序列化】JSON（Jackson2JsonMessageConverter），仅承载字段，不写业务逻辑。
 */
@Data
public class ModerationAppealResultEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等依据之一） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version;

    /** 审核业务类型（review 侧 ModerationBizTypeEnum code；6=图片） */
    private Integer bizType;

    /** 业务ID（图片时即 fileId） */
    private String bizId;

    /** 申诉单ID */
    private Long appealId;

    /** 裁决结果：true=改判PASS（移回原前缀）；false=维持驳回（保持隔离） */
    private Boolean appealPass;

    /** 关联审核任务ID */
    private Long reviewTaskId;
}