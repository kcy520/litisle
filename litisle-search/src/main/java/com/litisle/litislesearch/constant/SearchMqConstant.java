package com.litisle.litislesearch.constant;

/**
 * search 模块 MQ 拓扑常量（索引同步死信兜底 + 延迟重投，独立收口避免与 ES 字段/缓存键混杂）。
 *
 * 说明：可调参数（重试次数/退避间隔等）一律外置 yml 的 litisle.search.* 段（见 SearchProperties），
 * 本类只收口不适合外置的「结构命名」常量，避免业务代码字符串硬编码
 */
public final class SearchMqConstant {

    /** 工具类禁止实例化 */
    private SearchMqConstant() {
    }

    /**
     * 索引同步死信交换机（Direct，durable）：独立于 content/social 业务交换机，
     * Direct 精确匹配无通配语义，杜绝业务侧通配绑定误吸死信（与 file 模块 moderation.dlx 同一设计）
     */
    public static final String EXCHANGE_SEARCH_DLX = "litisle.search.dlx";

    /**
     * 索引同步死信队列（durable）：六个同步队列
     * （content.published / content.unpublished / content.article-updated / content.view-count-flushed /
     * social.liked / social.commented）消费重试耗尽后经 DLX 按原路由键统一汇入，供人工排查/重投
     */
    public static final String QUEUE_SEARCH_INDEX_DLQ = "litisle.search.index.dlq.queue";

    /**
     * 索引同步延迟重投交换机（x-delayed-message，durable，批次二 2.4）：
     * 消费异常时按指数退避把消息延迟投回原队列（routingKey=队列名），
     * 依赖 broker 启用 rabbitmq_delayed_message_exchange 插件（docker 已启用）
     */
    public static final String EXCHANGE_SEARCH_DELAY = "litisle.search.delay.exchange";

    /** 延迟重投交换机类型（RabbitMQ 延迟插件固定值，非普通 direct/topic 类型） */
    public static final String EXCHANGE_TYPE_DELAYED = "x-delayed-message";

    /** 延迟交换机参数键：实际投递类型（延迟插件要求显式声明底层交换机类型） */
    public static final String DELAYED_EXCHANGE_ARG_TYPE = "x-delayed-type";

    /** 延迟交换机底层投递类型值（Direct，与队列绑定键精确匹配） */
    public static final String DELAYED_EXCHANGE_TYPE_DIRECT = "direct";
}
