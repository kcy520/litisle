package com.litisle.commonmq.consume;

import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commonmq.send.MqSendUtil;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

/**
 * MQ 消费侧 traceId 恢复（补齐 commom-des.md §10.2 链路图的 MQ 缺口）。
 *
 * 【用法】在 @RabbitListener 方法体首行恢复、finally 清理：
 * <pre>
 *     MqTraceSupport.restore(message);
 *     try {
 *         // 业务处理，日志自动带 traceId
 *     } finally {
 *         MqTraceSupport.clear();
 *     }
 * </pre>
 *
 * 【说明】MQ 消费线程非 HTTP 线程，MDC 无自动来源；无 Header 时 traceId 为空，
 * 日志显示 [] 不影响运行（定时任务直投场景同理，与 TraceIdFilter 本地生成策略对齐）。
 * MDC 就是“当前线程的日志身份证袋”，你往里面塞一个 traceId，这个线程里所有日志都会自动带上它。
 *
 * 这是 RabbitMQ 消费端的 traceId 恢复与清理工具类，用来在 MQ 消费线程里“手动补齐链路追踪 ID”，让日志能串起 HTTP → MQ 的整条调用链。
 *
 *     TraceIdFilter 的作用域仅限于 HTTP 请求线程
 *     MQ 是一条「异步边界」，MDC 不会自动跨线程、跨进程、跨 broker。
 *     HTTP Filter 只能保证「请求内」链路；MQ 是异步边界，traceId 必须显式跨线程、跨进程传递。
 *
 */
public final class MqTraceSupport {

    private MqTraceSupport() {
    }

    /**
     * 从消息 Header 恢复 traceId 到 MDC（缺失时静默跳过，非 final 工具类禁止实例化）
     * 从 MQ 消息 Header 中取 traceId，写入当前线程的 MDC
     *
     * */
    public static void restore(Message message) {
        // 防御性判空：message 及其属性理论上由框架保证非空，此处兜底避免消费方法直接 NPE
        if (message == null || message.getMessageProperties() == null) {
            return;
        }
        // 步骤1：从消息属性中按与发送侧约定的同名 Header（HeaderConstant.TRACE_ID）读出 traceId
        Object traceId = message.getMessageProperties().getHeader(HeaderConstant.TRACE_ID);
        // 步骤2：有效时写入当前消费线程的 MDC——此后该线程内所有日志 pattern %X{traceId} 自动带上链路 ID；
        // 缺失（如旧消息/定时任务直投）时静默跳过，日志显示 [] 不影响运行
        if (traceId != null && !traceId.toString().isBlank()) {
            MDC.put(MqSendUtil.TRACE_ID_MDC_KEY, traceId.toString());
        }
    }

    /**
     *  清理 MDC，防 MQ 消费线程池复用导致 traceId 串扰（与 TraceIdFilter finally 清理同款纪律）
     * 把当前线程 MDC 里的 traceId 删掉，防止线程池串号
     * */
    public static void clear() {
        // MQ 消费线程由 SimpleMessageListenerContainer 的线程池复用，
        // 本条消息的 traceId 若不清除，会被下一条消息的日志误携带（串扰）；
        // 必须在 @RabbitListener 方法的 finally 中调用
        MDC.remove(MqSendUtil.TRACE_ID_MDC_KEY);
    }
}