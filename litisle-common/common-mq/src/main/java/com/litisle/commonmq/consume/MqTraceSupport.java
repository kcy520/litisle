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
 */
public final class MqTraceSupport {

    private MqTraceSupport() {
    }

    /** 从消息 Header 恢复 traceId 到 MDC（缺失时静默跳过） */
    public static void restore(Message message) {
        if (message == null || message.getMessageProperties() == null) {
            return;
        }
        Object traceId = message.getMessageProperties().getHeader(HeaderConstant.TRACE_ID);
        if (traceId != null && !traceId.toString().isBlank()) {
            MDC.put(MqSendUtil.TRACE_ID_MDC_KEY, traceId.toString());
        }
    }

    /** 清理 MDC，防 MQ 消费线程池复用导致 traceId 串扰（与 TraceIdFilter finally 清理同款纪律） */
    public static void clear() {
        MDC.remove(MqSendUtil.TRACE_ID_MDC_KEY);
    }
}