package com.litisle.litisleapi.event.content;


import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容发布成功事件（MQ 事件 Schema，契约库定义、content 服务发布、下游服务订阅）
 *
 * 【用途】
 * - search：建立/恢复 ES 索引
 * - ai：RAG 重新收录 chunk（消费端门控回查 status=2 且 visibility=公开）
 * - notification：发布结果通知作者
 *
 * 【触发路径】
 * 1. 审核判定 PASS 回写 1→2（板块八消费端接入）
 * 2. 免审直落 0→2（板块三 A2 分支接入）
 * 3. 转公开不重审（visibility 2/3/4→1 且 status=2，[4.1]：方案B 禁改正文 + content_hash 未变，
 *    原审核结论仍有效；不重建/不重写任何审核缓存，原键仍有效）
 *
 * 【实现步骤】（对齐 Api-Architecture.md §7.1 事件 Schema 规范）
 * 1. 实现 Serializable，字段如下（前 4 个为事件 Schema 公共字段，全部事件必须有）：
 *    - String  eventId         事件唯一 ID（UUID，消费端幂等去重）
 *    - LocalDateTime eventTime 事件发生时间
 *    - String  traceId         链路追踪 ID（从 MDC 透传）
 *    - Integer version         Schema 版本号（当前固定 1）
 * 2. 追加业务字段：
 *    - Integer bizType         业务类型（1-文章；预留评论扩展）
 *    - Long    bizId           业务 ID
 *    - String  contentHash     最近一次送审文本指纹（转公开场景未变，消费方按需留痕）
 * 3. 序列化 JSON，禁止 @Builder（项目 DTO 规范），@Data + 手动赋值；
 * 4. 发布时机：事务提交后发布（TransactionSynchronization afterCommit），
 *    Exchange=MqConstant.EXCHANGE_CONTENT，Routing=MqConstant.ROUTING_CONTENT_PUBLISHED；
 * 5. 消费端约定：eventId 幂等去重；消费失败重试耗尽入死信，不阻塞主流程。
 */
@Data
public class ContentPublishedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等去重） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID（从上下文透传） */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version;

    /** 业务类型（1-文章；预留评论扩展，口径同 review 侧 ModerationBizTypeEnum） */
    private Integer bizType;

    /** 业务 ID */
    private Long bizId;

    /** 最近一次送审文本指纹（转公开场景未变，消费方按需留痕） */
    private String contentHash;
}
