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

    // ==================== 审核模块（moderation）====================

    /** 审核模块交换机（moderation 侧发布审核/申诉结果，file 等下游订阅） */
    public static final String EXCHANGE_MODERATION = "litisle.moderation.exchange";

    /** 审核模块死信交换机（独立 DLX，Direct 类型：与业务交换机隔离，避免 Topic 通配绑定误吸死信） */
    public static final String EXCHANGE_MODERATION_DLX = "litisle.moderation.dlx";

    /** file 侧审核结果队列（回写 safe_flag / 隔离违规图） */
    public static final String QUEUE_FILE_MODERATION_RESULT = "litisle.file.moderation.result.queue";

    /** file 侧审核结果死信队列（消费失败兑底，防毒消息无限重投） */
    public static final String QUEUE_FILE_MODERATION_RESULT_DLQ = "litisle.file.moderation.result.dlq.queue";

    /** file 侧申诉结果队列（改判 PASS 移回原前缀） */
    public static final String QUEUE_FILE_MODERATION_APPEAL_RESULT = "litisle.file.moderation.appeal.result.queue";

    /** file 侧申诉结果死信队列 */
    public static final String QUEUE_FILE_MODERATION_APPEAL_RESULT_DLQ = "litisle.file.moderation.appeal.result.dlq.queue";

    /** 机审/人审结果路由键 */
    public static final String ROUTING_MODERATION_RESULT = "moderation.result";

    /** 审核结果死信路由键 */
    public static final String ROUTING_MODERATION_RESULT_DLQ = "moderation.result.dlq";

    /** 申诉改判结果路由键 */
    public static final String ROUTING_MODERATION_APPEAL_RESULT = "moderation.appeal.result";

    /** 申诉结果死信路由键 */
    public static final String ROUTING_MODERATION_APPEAL_RESULT_DLQ = "moderation.appeal.result.dlq";

    /** 图片送审路由键（file 上传预审发布，review 侧消费，文本/图片分队列可独立扩容） */
    public static final String ROUTING_MODERATION_IMAGE_SUBMIT = "moderation.image.submit";

    /** 文本送审路由键（content/message 发布侧同步 DFA 放行后异步送审，review 侧消费） */
    public static final String ROUTING_MODERATION_TEXT_SUBMIT = "moderation.text.submit";

    /** 文本送审死信路由键 */
    public static final String ROUTING_MODERATION_TEXT_SUBMIT_DLQ = "moderation.text.submit.dlq";

    /** 文本送审队列（review 侧消费，配 DLX 兜底防毒丸消息阻塞主队列） */
    public static final String QUEUE_MODERATION_TEXT_SUBMIT = "litisle.moderation.text.submit.queue";

    /** 文本送审死信队列（消费失败 nack requeue=false 后经 DLX 路由入此，防毒消息无限重投） */
    public static final String QUEUE_MODERATION_TEXT_SUBMIT_DLQ = "litisle.moderation.text.submit.dlq.queue";
}
