package com.litisle.litisleapi.event.moderation;


import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 图片送审事件（MQ 事件 Schema，契约库定义、file 服务发布、review 服务消费）
 *
 * 【发布时机】file 上传链路：sha256 黑名单/审核缓存均未命中时，put 完成置 status=1 后发布
 * （Review-Plan §4.2.2 ②），消费端按 bizType+bizId+scene 定位任务后回查图片，事件体不含图片地址
 *
 * 【事件 Schema 公共字段】（对齐 Api-Architecture.md §7.1，全部事件必须有）
 * - eventId    事件唯一 ID（UUID，消费端幂等去重）
 * - eventTime  事件发生时间
 * - traceId    链路追踪 ID（从 MDC 透传）
 * - version    Schema 版本号（当前固定 1）
 *
 * 【序列化】JSON（Jackson2JsonMessageConverter），禁止 @Builder（项目 DTO 规范），用 @Data + 手动赋值
 */
@Data
public class ImageSubmitEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等去重依据之一） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version;

    /** 审核方式（1-机审；送审链路当前仅机审） */
    private Integer modType;

    /** 审核业务类型（6=图片，口径见 ModerationConstant） */
    private Integer bizType;

    /** 业务ID（图片时即 fileId） */
    private String bizId;

    /** 审核场景（图片上传预审=IMAGE；scene 必带，防复审任务与文本任务同 bizType+bizId 定位歧义） */
    private String scene;

    /** 内容指纹（图片即 sha256，消费端缓存 key 派生/对账用） */
    private String contentHash;

    /** 重试次数（首次送审 0，延迟重投递增，消费端与 review_task.retry_count 对账） */
    private Integer retryCount;
}
