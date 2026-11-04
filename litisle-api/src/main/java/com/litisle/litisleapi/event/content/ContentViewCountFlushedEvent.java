package com.litisle.litisleapi.event.content;


import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文章浏览量落库事件（MQ 事件 Schema，契约库定义、content 服务发布、search 服务订阅）
 *
 * 【用途】
 * - search：覆盖更新 article_idx.view_cnt 并重算 heat 热度分（批次A A5 定案：
 *   heat 公式纳入浏览量，content 浏览量 flush 任务 ViewCountFlushJob 落库后补推本事件，
 *   使搜索热度加权与 content 热门榜口径对齐）
 *
 * 【触发路径】content 浏览量 flush 任务（ViewCountFlushJob）批量落库成功后补推
 * （契约先行，content 侧发布点随 flush 任务接入）：定时任务无事务包裹，直接发布
 *
 * 【实现步骤】（对齐 Api-Architecture.md §7.1 事件 Schema 规范）
 * 1. 实现 Serializable，字段如下（前 4 个为事件 Schema 公共字段，全部事件必须有）：
 *    - String  eventId         事件唯一 ID（UUID，消费端幂等去重）
 *    - LocalDateTime eventTime 事件发生时间
 *    - String  traceId         链路追踪 ID（从上下文透传）
 *    - Integer version         Schema 版本号（当前固定 1）
 * 2. 追加业务字段：bizType / bizId / viewCount（见下方字段注释）；
 *    viewCount 为落库后的最新绝对值（覆盖更新语义，非增量），消费端直接 SET 防重复消费累加错数；
 * 3. 序列化 JSON，禁止 @Builder（项目 DTO 规范），@Data + 手动赋值；
 * 4. 发布：Exchange=MqConstant.EXCHANGE_CONTENT，Routing=MqConstant.ROUTING_CONTENT_VIEW_COUNT_FLUSHED；
 * 5. 消费端约定：eventId 幂等去重；同文多次 flush 以到达顺序覆盖（绝对值 SET 与顺序无关）；
 *    消费失败重试耗尽入死信，不阻塞主流程。
 *
 * @author litisle
 */
@Data
public class ContentViewCountFlushedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等去重） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID（从上下文透传） */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version;

    /** 业务类型（1-文章；预留扩展，口径同 review 侧 ModerationBizTypeEnum） */
    private Integer bizType;

    /** 业务 ID（文章主键） */
    private Long bizId;

    /** 最新浏览量绝对值（content view_cnt 落库后的权威值，消费端覆盖 SET 而非累加） */
    private Long viewCount;
}
