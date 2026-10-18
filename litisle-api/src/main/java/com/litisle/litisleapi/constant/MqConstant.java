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

    /**
     * 内容下架事件队列（板块四 4-4.1）：content 删除/下架/转私密三路径发布，
     * ai 消费移除向量库 chunk、search 移除 ES 索引；缓存键失效由发布方本地执行不走本事件
     */
    public static final String QUEUE_CONTENT_UNPUBLISHED = "litisle.content.unpublished.queue";

    /**
     * 封禁连带下架队列（板块八 8.2）：user 服务发布 user.banned，content 消费
     * 将该作者全部已发布文章批量置 4-下架（配套逐篇发 content.unpublished + 本地缓存键失效）
     */
    public static final String QUEUE_CONTENT_USER_BANNED = "litisle.content.user.banned.queue";

    /** 封禁连带下架死信队列（消费重试耗尽兜底，复用 moderation.dlx；无 TTL、无二级 DLX） */
    public static final String QUEUE_CONTENT_USER_BANNED_DLQ = "litisle.content.user.banned.dlq.queue";

    // ==================== 路由键 ====================

    /** 用户注册路由键 */
    public static final String ROUTING_USER_REGISTERED = "user.registered";

    /** 用户封禁路由键（板块八 8.2，发布至 EXCHANGE_USER，事件体 UserBannedEvent） */
    public static final String ROUTING_USER_BANNED = "user.banned";

    /** 封禁连带下架死信路由键（复用 moderation.dlx 精确投递，防通配误吸） */
    public static final String ROUTING_USER_BANNED_DLQ = "user.banned.dlq";

    /** 文章发布路由键 */
    public static final String ROUTING_CONTENT_PUBLISHED = "content.published";

    /** 内容下架路由键（板块四 4-4.1，发布至 EXCHANGE_CONTENT，事件体 ContentUnpublishedEvent） */
    public static final String ROUTING_CONTENT_UNPUBLISHED = "content.unpublished";

    // ==================== 审核模块（moderation）====================

    /** 审核模块交换机（moderation 侧发布审核/申诉结果，file 等下游订阅） */
    public static final String EXCHANGE_MODERATION = "litisle.moderation.exchange";

    /** 审核模块死信交换机（独立 DLX，Direct 类型：与业务交换机隔离，避免 Topic 通配绑定误吸死信） */
    public static final String EXCHANGE_MODERATION_DLX = "litisle.moderation.dlx";

    /**
     * 审核延迟重试专用交换机（板块三 3-8.1，x-delayed-message 插件类型）：
     * 独立新建、不改动现有共享业务交换机（EXCHANGE_MODERATION 为双侧共用普通 TopicExchange，
     * file 侧已按该类型声明绑定，直接改造会触发 PRECONDITION_FAILED）；
     * 重投复用原送审路由键，消息按 x-delay 消息头延迟后落回原主队列，无需新增队列/路由键
     */
    public static final String EXCHANGE_MODERATION_DELAY = "litisle.moderation.delay.exchange";

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

    /**
     * 图片送审队列（review 侧消费；file 上传预审发布）。
     * <p>与文本送审分队列，可独立扩容；消息体 ImageSubmitEvent（bizType=6 / bizId=fileId / scene / contentHash），
     * 事件体不含图片地址——消费端一律走 FileClient.getContent(fileId) 内部字节流回查（SSRF 防线，§2.1）</p>
     */
    public static final String QUEUE_MODERATION_IMAGE_SUBMIT = "litisle.moderation.image.submit.queue";

    /** 图片送审死信队列（消费失败 nack requeue=false 后经 DLX 路由入此，防毒消息无限重投） */
    public static final String QUEUE_MODERATION_IMAGE_SUBMIT_DLQ = "litisle.moderation.image.submit.dlq.queue";

    /** 图片送审死信路由键（主队列 deadLetterRoutingKey，决定死信进图片 DLQ） */
    public static final String ROUTING_MODERATION_IMAGE_SUBMIT_DLQ = "moderation.image.submit.dlq";

    // ==================== 审核结果分发（板块三 3-4，§4.5.2）====================

    /**
     * 文本机审结果路由键（bizType∈{1-文章,2-评论}；4-作家申请亦走本键，无业务方消费者仅留链路）。
     * <p>content 侧（3-5）消费：文章双链路聚合 + 评论 CAS 回写</p>
     */
    public static final String ROUTING_MODERATION_TEXT_RESULT = "moderation.text.result";

    /** 私信机审结果路由键（bizType=5；message 侧消费：风控拦截/恢复） */
    public static final String ROUTING_MODERATION_MESSAGE_RESULT = "moderation.message.result";

    /**
     * 图片机审结果路由键（bizType=6）。
     * <p>⚠️ 联动改造点：file 侧 QUEUE_FILE_MODERATION_RESULT 现绑定 ROUTING_MODERATION_RESULT（moderation.result），
     * 本键启用后 file 侧 ModerationMqConfig 须将绑定同步改为 moderation.image.result，
     * 否则 safe_flag 回写/隔离链路收不到图片 result（拓扑改动两侧须参数对齐防 PRECONDITION_FAILED）</p>
     */
    public static final String ROUTING_MODERATION_IMAGE_RESULT = "moderation.image.result";

    // ==================== content 侧审核结果/申诉回写队列（板块三 3-5，§4.5.2）====================

    /**
     * content 侧审核结果队列（文章双链路聚合回写，3-5）。
     * <p>绑定 ROUTING_MODERATION_TEXT_RESULT：文章文本 result（scene=ARTICLE）与配图复审
     * result（scene=ARTICLE_IMG_RE_REVIEW，bizType 仍=1 复用文章任务）同队列到达，
     * 消费端按 scene 分流聚合；评论 bizType=2 随评论模块开发接线（当前留痕丢弃）</p>
     */
    public static final String QUEUE_CONTENT_MODERATION_TEXT_RESULT = "litisle.content.moderation.text.result.queue";

    /** content 侧审核结果死信队列（消费失败重试耗尽兜底，防毒丸消息无限重投） */
    public static final String QUEUE_CONTENT_MODERATION_TEXT_RESULT_DLQ = "litisle.content.moderation.text.result.dlq.queue";

    /** content 侧审核结果死信路由键（主队列 deadLetterRoutingKey，决定死信进 content DLQ） */
    public static final String ROUTING_MODERATION_TEXT_RESULT_DLQ = "moderation.text.result.dlq";

    /** content 侧申诉改判结果队列（5-4.5：文章改判 PASS → CAS 3→2 恢复发布 + last-hash 失效） */
    public static final String QUEUE_CONTENT_MODERATION_APPEAL_RESULT = "litisle.content.moderation.appeal.result.queue";

    /** content 侧申诉改判结果死信队列（消费失败重试耗尽兜底） */
    public static final String QUEUE_CONTENT_MODERATION_APPEAL_RESULT_DLQ = "litisle.content.moderation.appeal.result.dlq.queue";

    // ==================== 处罚结果分发（板块五 5-2，§4.2.3/§4.5.2）====================

    /**
     * 处罚结果路由键（5-2.1：punish_log 落库同一本地事务内发布；事件仅触发信号，
     * auth/user 消费后经 Feign 回查权威态覆盖本地快照，幂等与到达顺序无关）
     */
    public static final String ROUTING_MODERATION_PUNISH = "moderation.punish.result";

    /**
     * auth 侧处罚结果队列（5-2.2/B1：固定单消费者串行消费（B4），扩容按 userId 一致性哈希；
     * 消费失败不 ACK 经 DLX 入 DLQ，双向对账 sweeper（5-3.2）兑底补漏）
     */
    public static final String QUEUE_AUTH_MODERATION_PUNISH = "litisle.auth.moderation.punish.queue";

    /** auth 侧处罚结果死信队列（消费失败 nack requeue=false 后经 DLX 路由入此，防毒消息无限重投） */
    public static final String QUEUE_AUTH_MODERATION_PUNISH_DLQ = "litisle.auth.moderation.punish.dlq.queue";

    /** auth 侧处罚结果死信路由键（主队列 deadLetterRoutingKey，决定死信进 DLQ） */
    public static final String ROUTING_MODERATION_PUNISH_DLQ = "moderation.punish.result.dlq";
}
