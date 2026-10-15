package com.litisle.litisleapi.event.moderation;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文本送审事件（MQ 事件 Schema，契约库定义、content/message 服务发布、review 服务消费）
 *
 * 【发布时机】发布侧同步 DFA 闸门放行后（未命中 HIGH）异步送审（Review-Plan §4.2.1），
 * 消费端按 bizType+bizId+scene 定位任务后回查正文，事件体不含正文
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
public class TextSubmitEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等去重依据之一） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version ;

    /** 审核方式（1-机审；送审链路当前仅机审） */
    private Integer modType;

    /** 审核业务类型（1-文章 2-评论 3-用户名 5-私信，口径见 ModerationBizTypeEnum） */
    private Integer bizType;

    /** 业务ID（文章/评论/私信主键；USERNAME 场景用户尚不存在约定为 0） */
    private Long bizId;

    /** 审核场景（ARTICLE/COMMENT/USERNAME/MESSAGE；scene 必带，防同 bizType+bizId 复审任务定位歧义） */
    private String scene;

    /**
     * 内容指纹（送审文本 SHA256，消费端缓存 key 派生/幂等比对用）；
     * 4-1.2 口径：送审文本=标题+正文拼接（title+分隔符+body，title 空取 body，SSOT 见 SubmitTextAssembler），
     * 标题必须一并进 hash/DFA/模型，防违规藏标题躲过 LLM 漏审
     */
    private String contentHash;

    /** 重试次数（首次送审 0，延迟重投递增，消费端与 review_task.retry_count 对账） */
    private Integer retryCount;
}
