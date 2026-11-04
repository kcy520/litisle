package com.litisle.litisleapi.event.content;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 书籍索引变更事件（MQ 事件 Schema，契约库定义、content 服务发布、search 服务订阅）。
 *
 * 【用途】search 消费后回查书籍权威数据，维护 book_idx 索引：
 * - 书籍创建/编辑（书名/简介/分类/封面等）→ upsert 书籍索引文档
 * - 书籍完结（状态流转，当前无独立端点，后续端点落地时复用本事件）
 * - 书籍下架/删除 → 回查不可用或门控不通过 → 删除索引文档
 *
 * 【触发路径】content 侧 BookServiceImpl 的创建/编辑/删除三个写入口，
 * 全部在本地事务提交后（afterCommit）发布（防回滚产生脏事件）。
 *
 * 【事件轻量纪律】事件体只带定位键 bookId，书名/作者/简介等元数据一律由消费端
 * 经 ContentClient#getBookIndexDoc 回查权威值，禁止在事件体塞大字段（防事件体被篡改/字段过期）。
 *
 * @author litisle
 */
@Data
public class ContentBookChangedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等去重与日志定位） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID（从 MDC 透传，串联 content → MQ → search 全链路日志） */
    private String traceId;

    /** Schema 版本号（当前固定 1，兼容未来字段扩展） */
    private Integer version;

    /** 书籍 ID（唯一定位键，消费端据此回查书籍权威数据） */
    private Long bookId;
}
