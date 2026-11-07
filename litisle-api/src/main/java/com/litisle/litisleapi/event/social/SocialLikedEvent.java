package com.litisle.litisleapi.event.social;


import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 点赞成功事件（MQ 事件 Schema，契约库定义、social 服务发布、search 服务订阅）
 *
 * 【用途】
 * - search：article_idx 点赞计数增量更新（partial update like_cnt）→ 重算 heat 热度分
 *   （heat 公式见 Search-Architecture.md §四，批次A A5 定案）
 *
 * 【触发路径】social 点赞落库成功后发布（temp search批次B B2 契约先行，social 落地时接入）：
 * 事务提交后发布（TransactionSynchronization afterCommit），防事务回滚后产生脏事件
 *
 * 【实现步骤】（对齐 Api-Architecture.md §7.1 事件 Schema 规范）
 * 1. 实现 Serializable，字段如下（前 4 个为事件 Schema 公共字段，全部事件必须有）：
 *    - String  eventId         事件唯一 ID（UUID，消费端幂等去重）
 *    - LocalDateTime eventTime 事件发生时间
 *    - String  traceId         链路追踪 ID（从 MDC 透传）
 *    - Integer version         Schema 版本号（当前固定 1）
 * 2. 追加业务字段：bizType / bizId / bizUserId（见下方字段注释）
 * 3. 序列化 JSON，禁止 @Builder（项目 DTO 规范），@Data + 手动赋值；
 * 4. 发布：Exchange=MqConstant.EXCHANGE_SOCIAL，Routing=MqConstant.ROUTING_SOCIAL_LIKED；
 * 5. 消费端约定：eventId 幂等去重；消费失败重试耗尽入死信，不阻塞主流程。
 *
 * @author litisle
 */
@Data
public class SocialLikedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等去重） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID（从上下文透传） */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version;

    /** 业务类型（1-文章 2-评论，口径同 review 侧 ModerationBizTypeEnum） */
    private Integer bizType;

    /** 业务 ID（被点赞的文章/评论主键） */
    private Long bizId;

    /** 互动发起用户 ID（点赞人；消费端按需留痕，不参与索引字段更新） */
    private Long bizUserId;

    /**
     * 点赞计数增量（批次十一 11.14：点赞事件固定 +1）：
     * 消费端统一按本字段累加 article_idx.like_cnt，旧消息缺失时按 +1 兜底
     */
    private Integer delta;
}
