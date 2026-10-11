package com.litisle.litisleapi.event.content;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内容下架事件（MQ 事件 Schema，契约库定义、content 服务发布、下游服务订阅）
 *
 * 【用途】
 * - ai：移除向量库中该文章对应 chunk（下架内容禁止继续被 RAG 检索，§2.1）
 * - search：移除 ES 索引（后续扩展）
 *
 * 【触发路径】（板块四 4-4.1，三路径缺一不可）
 * 1. 作者删除（del_flag=1）
 * 2. 下架（status=4；含复审驳回 2→4、作者封禁连带下架）
 * 3. 转私密（visibility=PRIVATE）
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
 *    - String  contentHash     最近一次送审文本指纹（下游按需留痕；缓存键失效由
 *                              发布方本地执行，见 ContentUnpublishedInvalidator，不走本事件）
 *    - Integer reason          下架原因（1-删除 2-下架 3-转私密，消费方按需分流）
 * 3. 序列化 JSON，禁止 @Builder（项目 DTO 规范），@Data + 手动赋值；
 * 4. 发布时机（content 侧删除/下架/转私密三方法）：事务提交后发布
 *    （@TransactionalEventListener AFTER_COMMIT），Exchange=MqConstant.EXCHANGE_CONTENT，
 *    Routing=MqConstant.ROUTING_CONTENT_UNPUBLISHED；
 * 5. 【铁律】发事件的同一事务/紧随其后，发布方必须本地执行缓存键失效
 *    （ContentUnpublishedInvalidator.invalidateAll），防「PASS→人工/法务下架→删除→重发同内容
 *    （新 bizId 同 hash）」命中旧 PASS 缓存绕过下架决定——历史已定性的攻击面，必须闭环（§2.1）；
 * 6. 消费端约定：eventId 幂等去重；消费失败重试耗尽入死信，不阻塞主流程。
 */
@Data
public class ContentUnpublishedEvent implements Serializable {

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

    /** 最近一次送审文本指纹（下游按需留痕；缓存键失效由发布方本地执行，不走本事件） */
    private String contentHash;

    /** 下架原因（1-删除 2-下架 3-转私密，消费方按需分流） */
    private Integer reason;
}
