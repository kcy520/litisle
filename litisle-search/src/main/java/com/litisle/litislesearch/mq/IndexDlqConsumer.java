package com.litisle.litislesearch.mq;

import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.litislesearch.constant.SearchBizConstant;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 索引同步死信消费者（批次二 2.4 兜底）。
 *
 * 【职责】监听 litisle.search.index.dlq.queue，把重试耗尽的死信记录成 error 日志（含原队列、
 * 重试次数、消息体摘要），供人工排查后按需重投；本类不做自动修复，也绝不静默丢弃而不留痕。
 *
 * 【死信来源】六个索引同步队列消费失败 nack(requeue=false) 后经死信交换机
 * litisle.search.dlx（Direct，绑定键=队列名）汇入本队列。
 *
 * 【ACK 纪律】死信队列本身不配 DLX（防死信再死信的循环），因此消费成功 ack 即物理出队；
 * 业务异常同样由 MqAckHelper ack 丢弃，任务残留的索引差异由后续重发/重建补齐。
 */
@Slf4j
@Component
public class IndexDlqConsumer {

    /** x-death 消息头键（RabbitMQ 死信审计信息：原队列、原因、次数） */
    private static final String HEADER_X_DEATH = "x-death";

    /** 日志中消息体最大打印字符数（防超长正文刷屏，只留排查定位片段） */
    private static final int BODY_LOG_MAX_CHARS = 500;

    /**
     * 消费索引同步死信：记录告警日志后确认出队。
     *
     * @param message 原始死信消息（死信体保留了原始事件 JSON 与 x-death 头）
     * @param channel 消费通道
     * @throws IOException ack 失败向上抛出
     */
    @RabbitListener(queues = SearchBizConstant.QUEUE_SEARCH_INDEX_DLQ)
    public void onIndexDlq(Message message, Channel channel) throws IOException {
        // 步骤1：死信头保留原链路 traceId，恢复后日志可回溯到当初的消费尝试
        MqTraceSupport.restore(message);
        try {
            // 步骤2：统一 ACK 模板，业务异常也 ack 丢弃（DLQ 无 DLX，nack 即物理丢弃）
            MqAckHelper.executeWithAck(SearchBizConstant.QUEUE_SEARCH_INDEX_DLQ, message, channel,
                    () -> logDeadLetter(message));
        } finally {
            // 步骤3：清理 MDC，防消费线程复用串扰
            MqTraceSupport.clear();
        }
    }

    /**
     * 打印死信详情（告警留痕），不抛异常。
     *
     * @param message 死信消息
     */
    private void logDeadLetter(Message message) {
        // x-death 里记录了死信来源队列与原因，取最近一条用于定位是哪条同步队列失败
        String originQueue = extractOriginQueue(message);
        String body = truncateBody(message);
        log.error("[索引同步死信] 消息重试耗尽，需人工排查/重投：originQueue={}, body={}", originQueue, body);
    }

    /**
     * 从 x-death 头提取死信来源队列（缺失时返回 unknown）。
     *
     * @param message 死信消息
     * @return 原队列名
     */
    @SuppressWarnings("unchecked")
    private String extractOriginQueue(Message message) {
        Object deathHeader = message.getMessageProperties().getHeader(HEADER_X_DEATH);
        if (!(deathHeader instanceof List<?> deaths) || deaths.isEmpty()) {
            return "unknown";
        }
        Object first = deaths.get(0);
        if (!(first instanceof Map<?, ?> death)) {
            return "unknown";
        }
        Object queue = death.get("queue");
        return queue == null ? "unknown" : queue.toString();
    }

    /**
     * 截断消息体用于日志输出（死信体是原始事件 JSON）。
     *
     * @param message 死信消息
     * @return 截断后的消息体文本
     */
    private String truncateBody(Message message) {
        byte[] body = message.getBody();
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8);
        return text.length() > BODY_LOG_MAX_CHARS ? text.substring(0, BODY_LOG_MAX_CHARS) + "..." : text;
    }
}
