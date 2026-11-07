package com.litisle.litisleapi.event.social;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 取消点赞事件（MQ 事件 Schema，契约库定义、content 侧临时承载发布、search 服务订阅）。
 *
 * 【用途】
 * - search：article_idx 点赞计数增量更新（delta=-1，partial update like_cnt）→ 重算 heat
 * - 与 {@link SocialLikedEvent}（+1）分路由键/队列，正负事件可分别观测、分别重投
 *
 * 【触发路径】content 临时承载点赞功能（批次十一 11.8），取消点赞物理删除 user_like 行成功后，
 * 本地事务提交后发布（afterCommit），防事务回滚后产生脏事件
 *
 * 【实现约定】（对齐 Api-Architecture.md §7.1 事件 Schema 规范）
 * 1. 实现 Serializable，前 4 个字段为事件公共字段（eventId/eventTime/traceId/version）；
 * 2. 业务字段沿用现有 social 事件口径：bizType（1-文章）、bizId（文章 ID）、bizUserId（操作人）；
 * 3. delta 固定 -1（消费端仍按字段读取，兼容后续批量/补偿场景）；
 * 4. 发布：Exchange=MqConstant.EXCHANGE_SOCIAL，Routing=MqConstant.ROUTING_SOCIAL_UNLIKED；
 * 5. 消费端 eventId 幂等去重，失败重试耗尽入死信，不阻塞主流程。
 *
 * @author litisle
 */
@Data
public class SocialUnlikedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等去重） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID（从上下文透传） */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version;

    /** 业务类型（1-文章，口径同 review 侧 ModerationBizTypeEnum） */
    private Integer bizType;

    /** 业务 ID（被取消点赞的文章主键） */
    private Long bizId;

    /** 互动发起用户 ID（取消点赞人；消费端按需留痕，不参与索引字段更新） */
    private Long bizUserId;

    /**
     * 点赞计数增量（批次十一 11.14：取消点赞事件固定 -1）：
     * 消费端统一按本字段累加 article_idx.like_cnt，并对结果做下限保护（不允许减成负数）
     */
    private Integer delta;
}
