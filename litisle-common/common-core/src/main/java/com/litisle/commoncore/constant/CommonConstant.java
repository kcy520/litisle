package com.litisle.commoncore.constant;


/**
 * 通用常量类
 * 
 * 【设计说明】
 * 1. 存放跨模块共享的通用常量
 * 2. 避免硬编码，提高可维护性
 * 3. 所有常量使用 static final 修饰，确保不可变
 * 
 * @author litisle
 */
public class CommonConstant {

    /**
     * UTF-8 字符集
     */
    public static final String UTF8 = "UTF-8";

    /**
     * 默认分页大小
     */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 最大分页大小（防止一次性查询过多数据）
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 成功标识
     */
    public static final int SUCCESS = 0;

    /**
     * 失败标识
     */
    public static final int FAIL = 1;

    /**
     * Bearer Token 类型（RFC 6750）
     * 用于 HTTP Authorization 请求头：Authorization: Bearer <token>
     * Bearer 来自 OAuth 2.0 规范（RFC 6750），全称是 Bearer Token。
     * 英文 bear = 持有，bearer = 持有者。
     * "谁持有这个 token，谁就是合法用户。"
     */
    public static final String TOKEN_TYPE_BEARER = "Bearer";

    /**
     * MDC 链路追踪 Key（日志上下文中的 traceId 键名）
     * 用于 TraceIdFilter 写入、FeignAuthInterceptor/MqSendUtil 读取，
     * 统一收敛避免各模块硬编码 "traceId" 字符串导致口径漂移
     */
    public static final String MDC_TRACE_ID_KEY = "traceId";

    /**
     * 未知占位值：来源标识/错误码等无法解析时的统一兜底
     * 用于 MQ 死信来源队列缺失、回执错误码缺失等场景，
     * 统一收敛避免各模块硬编码 "unknown" 字符串导致口径漂移
     */
    public static final String UNKNOWN = "unknown";

    /**
     * x-death 消息头键（RabbitMQ 死信审计头）
     * 消息经死信交换机流转时由 RabbitMQ 自动写入，内含原队列、死信原因、死信次数的列表；
     * 与 MDC_TRACE_ID_KEY 同性质，收敛到 core 供各模块 MQ 消费者共用
     */
    public static final String HEADER_X_DEATH = "x-death";

    /**
     * x-death 列表元素中"来源队列"键
     * 用于定位死信出自哪条同步/业务队列，缺失时以 UNKNOWN 兜底
     */
    public static final String X_DEATH_KEY_QUEUE = "queue";
}