package com.litisle.litisleapi.event.content;


import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 已发布文章元数据更新事件（MQ 事件 Schema，契约库定义、content 服务发布、search 服务订阅）
 *
 * 【用途】
 * - search：轻量回查元数据后 partial update article_idx 元数据字段（title/tags/summary 等），
 *   不动向量与 chunks（temp search批次B B7，批次A A2 定案：
 *   content 方案B 允许 status=2 文章编辑元数据，编辑后若不发事件索引字段将永久过期）
 *
 * 【触发路径】content 已发布文章元数据编辑（title/tags/summary/cover）成功后发布
 * （契约先行，content 侧发布点随元数据编辑链路接入）：
 * 事务提交后发布（TransactionSynchronization afterCommit），防事务回滚后产生脏事件
 *
 * 【实现步骤】（对齐 Api-Architecture.md §7.1 事件 Schema 规范）
 * 1. 实现 Serializable，字段如下（前 4 个为事件 Schema 公共字段，全部事件必须有）：
 *    - String  eventId         事件唯一 ID（UUID，消费端幂等去重）
 *    - LocalDateTime eventTime 事件发生时间
 *    - String  traceId         链路追踪 ID（从 MDC 透传）
 *    - Integer version         Schema 版本号（当前固定 1）
 * 2. 追加业务字段：bizType / bizId / contentHash（见下方字段注释）；
 *    事件体不携带元数据内容（禁事件体塞大字段），search 收到后经 ContentClient 回查权威值；
 * 3. 序列化 JSON，禁止 @Builder（项目 DTO 规范），@Data + 手动赋值；
 * 4. 发布：Exchange=MqConstant.EXCHANGE_CONTENT，Routing=MqConstant.ROUTING_CONTENT_ARTICLE_UPDATED；
 * 5. 消费端约定：eventId 幂等去重；消费失败重试耗尽入死信，不阻塞主流程。
 *
 * @author litisle
 */
@Data
public class ContentArticleUpdatedEvent implements Serializable {

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

    /**
     * 最近一次送审文本指纹（元数据编辑不改正文，本值不变；
     * 保留作定位键对齐 ContentPublishedEvent 纪律，消费方按需留痕防旧消息错配）
     */
    private String contentHash;
}
