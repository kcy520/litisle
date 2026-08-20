package com.litisle.litisleapi.constant;

/**
 * MQ 交换机、队列、路由键命名规范常量。
 *
 * 【命名规范】
 * 交换机：litisle.{模块}.exchange
 * 队列：litisle.{模块}.{业务}.queue
 * 路由键：{模块}.{业务事件}
 *
 * @author litisle
 */
public class MqConstant {

    // ==================== 交换机 ====================

    /** 用户模块交换机 */
    public static final String EXCHANGE_USER = "litisle.user.exchange";

    /** 内容模块交换机 */
    public static final String EXCHANGE_CONTENT = "litisle.content.exchange";

    /** 通知模块交换机 */
    public static final String EXCHANGE_NOTIFICATION = "litisle.notification.exchange";

    // ==================== 队列 ====================

    /** 用户注册事件队列 */
    public static final String QUEUE_USER_REGISTERED = "litisle.user.registered.queue";

    /** 文章发布事件队列 */
    public static final String QUEUE_CONTENT_PUBLISHED = "litisle.content.published.queue";

    // ==================== 路由键 ====================

    /** 用户注册路由键 */
    public static final String ROUTING_USER_REGISTERED = "user.registered";

    /** 文章发布路由键 */
    public static final String ROUTING_CONTENT_PUBLISHED = "content.published";
}
