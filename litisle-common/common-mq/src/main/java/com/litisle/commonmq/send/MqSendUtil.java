package com.litisle.commonmq.send;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * MQ 发送工具（common-mq 统一收口，业务服务禁止直接裸调 RabbitTemplate.convertAndSend）。
 *
 * 【两档发送语义】（按「调用方是否需要对发送失败做出反应」选择，不是策略矩阵，防过度设计）
 * 1. {@link #send(String, String, Object)}：尽力而为——失败仅记 error 不抛。
 *    适用于失败有自然兜底路径的事件（如 image.submit 发布失败 → safe_flag 停留占位态，
 *    闸口不放行 + 对账任务兜底，主流程无需回滚）
 * 2. {@link #sendCritical(String, String, Object)}：必达语义——内部静默重试 1 次后仍失败则抛
 *    BizException(MQ_SEND_FAILED)，强制调用方表态（回滚事务 / 执行补偿逻辑）。
 *    适用于「失败会破坏数据一致性」的事件（如 content.unpublished：review 侧 REJECT 后
 *    发丢会导致向量库 chunk 残留，RAG 检索到已下架内容——项目纪律明确「必须发送」）
 *
 * 【通用能力】两档共用：traceId 注入消息 Header（HeaderConstant.TRACE_ID，与 Feign/HTTP
 * 透传同一口径，消费侧由 MqTraceSupport 恢复，补齐 commom-des.md §10 链路图 MQ 缺口）
 *
 * 【暂不做】（防过度设计，附触发条件）
 * - Publisher Confirm（broker 确认）：需 publisher-confirm-type: correlated + 异步回调管理；
 *   触发条件：出现 broker 丢消息造成真实业务事故时再做
 * - 本地消息表/事务性发件箱：支付扣款级强一致方案（已定 Seata AT 覆盖积分商城），发送侧不重造
 *
 * 【使用示例】
 * <pre>
 *     private final MqSendUtil mqSendUtil;
 *
 *     // 尽力而为
 *     mqSendUtil.send(MqConstant.EXCHANGE_MODERATION,
 *             MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT, event);
 *
 *     // 必达语义（调用方需 try-catch 走补偿，或让事务随异常回滚）
 *     mqSendUtil.sendCritical(MqConstant.EXCHANGE_CONTENT,
 *             MqConstant.ROUTING_CONTENT_UNPUBLISHED, event);
 * </pre>
 */
@Slf4j
public class MqSendUtil {

    /** MDC key：与 common-security TraceIdFilter / FeignAuthInterceptor 的 traceId 写入 key 保持一致（SSOT 为日志 pattern %X{traceId}） */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /** sendCritical 必达语义的静默重试次数（瞬时网络抖动重试一次，低成本提升必达概率，不引入异步任务框架） */
    private static final int CRITICAL_MAX_RETRY = 1;

    private final RabbitTemplate rabbitTemplate;

    public MqSendUtil(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 尽力而为发送：traceId 注入 Header；失败仅记 error 不抛。
     *
     * <p>适用于失败有自然兜底路径的事件。⚠️ 不适合「必须发出去」的场景
     * （如 content.unpublished），那类场景请用 {@link #sendCritical(String, String, Object)}</p>
     *
     * @param exchange   目标交换机（MqConstant.EXCHANGE_*）
     * @param routingKey 路由键（MqConstant.ROUTING_*）
     * @param event      事件体（litisle-api 事件 DTO，经 Jackson2JsonMessageConverter 序列化）
     */
    public void send(String exchange, String routingKey, Object event) {
        // 步骤1：发送前先从 MDC 取出当前线程的 traceId（HTTP 请求场景由 TraceIdFilter 写入，
        // Feign 调用场景由上游透传；定时任务/无请求上下文场景可能为 null）。
        // 注意：必须在 lambda 外先取值——lambda 内无法引用非 final 局部变量，且发送时机线程可能与当前线程不同
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        try {
            doSend(exchange, routingKey, event, traceId);
            // 步骤3：发布成功记录 info 日志（含 exchange/routingKey/traceId，便于发送侧审计）
            log.info("MQ 事件已发布，exchange={}，routingKey={}，traceId={}", exchange, routingKey, traceId);
        } catch (Exception e) {
            // 失败口径：仅记 error 不抛——业务事件发布失败不阻断主流程
            // （对齐既有纪律：如 file 侧 image.submit 发布失败不回滚上传，safe_flag 停留占位态，
            // 后续由消费方重试/对账任务兜底）
            log.error("MQ 事件发布失败（不阻断主流程），exchange={}，routingKey={}，traceId={}",
                    exchange, routingKey, traceId, e);
        }
    }

    /**
     * 必达语义发送：失败静默重试 {@value CRITICAL_MAX_RETRY} 次后仍失败则抛
     * BizException(MQ_SEND_FAILED)，强制调用方表态（回滚事务 / 执行补偿逻辑）。
     *
     * <p>适用于「失败会破坏数据一致性」的事件（如 content.unpublished、审核结果回写类）。
     * 抛异常而非返回 boolean 的原因：boolean 返回值容易被忽略（不写 if 判断），
     * 抛异常则调用方必须 try-catch 或让事务回滚，与「宁严勿松」纪律一致。</p>
     *
     * @param exchange   目标交换机（MqConstant.EXCHANGE_*）
     * @param routingKey 路由键（MqConstant.ROUTING_*）
     * @param event      事件体（litisle-api 事件 DTO，经 Jackson2JsonMessageConverter 序列化）
     * @throws BizException 重试后仍发送失败（ErrorCode.MQ_SEND_FAILED，cause 为底层异常）
     */
    public void sendCritical(String exchange, String routingKey, Object event) {
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        // 步骤1：首次发送 + 最多 CRITICAL_MAX_RETRY 次静默重试（瞬时网络抖动场景，
        // 重试间隔极短不做退避——broker 连接类故障重试也大概率失败，快速抛出让调用方决定走向）
        for (int attempt = 0; attempt <= CRITICAL_MAX_RETRY; attempt++) {
            try {
                doSend(exchange, routingKey, event, traceId);
                log.info("MQ 必达事件已发布，exchange={}，routingKey={}，traceId={}，attempt={}",
                        exchange, routingKey, traceId, attempt);
                return;
            } catch (Exception e) {
                if (attempt < CRITICAL_MAX_RETRY) {
                    // 重试前记录 warn（含异常摘要，排查时能区分「首发送败重试成功」与「重试后仍失败」）
                    log.warn("MQ 必达事件发送失败，准备重试（attempt={}/{}），exchange={}，traceId={}，cause={}",
                            attempt, CRITICAL_MAX_RETRY, exchange, traceId, e.getMessage());
                } else {
                    // 步骤2：重试耗尽 → error 留痕后抛 BizException 强制调用方表态：
                    // 事务回滚 / 本地补偿 / 告警人工介入，禁止静默丢失破坏一致性的事件
                    log.error("MQ 必达事件发送失败（重试耗尽），exchange={}，routingKey={}，traceId={}",
                            exchange, routingKey, traceId, e);
                    throw new BizException(ErrorCode.MQ_SEND_FAILED, e);
                }
            }
        }
    }

    /**
     * 发送主体（两档语义共用收口）：convertAndSend + traceId 注入消息 Header。
     * 异常向调用方上抛，由 send（吞掉记日志）或 sendCritical（重试后抛出）决定口径
     */
    private void doSend(String exchange, String routingKey, Object event, String traceId) {
        // 步骤2：convertAndSend + MessagePostProcessor 后置处理器。
        // 第四个参数 lambda 在消息序列化前被回调，可对原始 Message 做加工——
        // 这里把 traceId 写入消息属性 Header（HeaderConstant.TRACE_ID），
        // 消费侧由 MqTraceSupport 从同名 Header 读回恢复，实现跨 MQ 链路追踪
        rabbitTemplate.convertAndSend(exchange, routingKey, event, message -> {
            // Header 为空时不写入，避免消息属性里出现 null/空串脏数据
            if (traceId != null && !traceId.isBlank()) {
                message.getMessageProperties().setHeader(HeaderConstant.TRACE_ID, traceId);
            }
            // MessagePostProcessor 约定：必须返回（可能已修改的）消息对象
            return message;
        });
    }
}