package com.litisle.litislecontent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * event_outbox 实体（litisle_content 库事件发件箱表，批次3 2026-11-02 本地 outbox）
 * <p>设计原因：content.published/unpublished 等事件丢失会导致 search 索引漏建/残留、ai RAG 摄入缺失，
 * 且无自然兜底路径——生产者发送前先把事件快照落本表（status=0 待发送），发送成功置 1、失败置 2，
 * 由 EventOutboxResendJob 定时扫描重发，兜住「发送前崩溃」「发送失败」两类事件丢失；
 * 消费端按 eventId 幂等去重，重发无副作用</p>
 */
@Data
@Accessors(chain = true)
@TableName("event_outbox")
public class EventOutbox implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== status 状态字典（本表私有，仅 ContentEventProducer / EventOutboxResendJob 读写） ====================

    /** 状态：0-待发送（生产者落表初始态；含「发送前崩溃」留下的悬置行） */
    public static final int STATUS_PENDING = 0;

    /** 状态：1-已发送（发送成功，等待按保留天数清理） */
    public static final int STATUS_SENT = 1;

    /** 状态：2-发送失败（等待重发 job 重试） */
    public static final int STATUS_FAILED = 2;

    /** 状态：3-重试超限放弃（死信留痕，需人工介入；仅 error 日志告警不再重试） */
    public static final int STATUS_ABANDONED = 3;

    /**
     * 主键ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 事件唯一ID（UUID，与事件体 eventId 同值）
     * <p>消费端幂等去重锚点，重发不改 eventId</p>
     */
    @TableField("event_id")
    private String eventId;

    /**
     * 事件类型（事件体类简名，如 ContentPublishedEvent）
     * <p>日志排查定位用，不参与逻辑</p>
     */
    @TableField("event_type")
    private String eventType;

    /**
     * 事件体 Java 类全名
     * <p>重发时按此名反序列化 payload JSON 还原事件对象</p>
     */
    @TableField("payload_type")
    private String payloadType;

    /**
     * 目标交换机（MqConstant.EXCHANGE_*）
     */
    @TableField("exchange")
    private String exchange;

    /**
     * 路由键（MqConstant.ROUTING_*）
     */
    @TableField("routing_key")
    private String routingKey;

    /**
     * 事件体 JSON 快照
     * <p>事件体均为小对象（submitted 不含正文，published/unpublished 仅指纹与业务ID），TEXT 足够</p>
     */
    @TableField("payload")
    private String payload;

    /**
     * 状态：0-待发送 1-已发送 2-发送失败 3-重试超限放弃
     */
    @TableField("status")
    private Integer status;

    /**
     * 重发次数（重发 job 累计，达上限置 3 放弃）
     */
    @TableField("retry_count")
    private Integer retryCount;

    /**
     * 创建时间（生产者发送前落表时刻）
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 成功发送时间（status=1 时有值）
     */
    @TableField("send_time")
    private LocalDateTime sendTime;
}
