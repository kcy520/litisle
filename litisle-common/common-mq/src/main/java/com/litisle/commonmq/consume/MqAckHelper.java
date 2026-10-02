package com.litisle.commonmq.consume;


import com.litisle.commoncore.exception.BizException;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;

import java.io.IOException;

/**
 * 手动 ACK 模板（common-mq SSOT，业务消费者禁止手写 try-catch-ack-nack 模板，防复制漂移）。
 *
 * 【ACK 纪律】（与 file 侧 ModerationResultListener 定稿口径一致）
 * - 业务成功：ack
 * - 业务性失败（BizException，如目标数据不存在/状态不满足）：warn 后 ack 丢弃，防毒丸消息无限重投
 * - 非预期异常（其他 Exception）：error 后 nack(requeue=false) 经 DLX 入 DLQ 兜底
 *
 * 【使用示例】
 * <pre>
 *     &#64;RabbitListener(queues = MqConstant.QUEUE_XXX)
 *     public void onXxx(XxxEvent event, Message message, Channel channel) throws IOException {
 *         MqTraceSupport.restore(message);
 *         try {
 *             MqAckHelper.executeWithAck("litisle.xxx.queue", message, channel,
 *                     () -> xxxService.handle(event));
 *         } finally {
 *             MqTraceSupport.clear();
 *         }
 *     }
 * </pre>
 */
@Slf4j
public final class MqAckHelper {

    private MqAckHelper() {
    }

    /**
     * 执行业务逻辑并按 ACK 纪律确认消息
     *
     * @param queue    队列名（日志定位用）
     * @param message  原始消息（取 deliveryTag）
     * @param channel  消费通道
     * @param business 业务逻辑（建议捕获不了_checked异常；业务校验抛 BizException 即按「业务性失败」处理）
     * @throws IOException ack/nack IO 异常向上抛出，交由容器按连接异常处理
     */
    public static void executeWithAck(String queue, Message message, Channel channel, Runnable business)
            throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            business.run();
            channel.basicAck(tag, false);
        } catch (BizException e) {
            log.warn("MQ 消息业务性失败，确认丢弃（防毒丸），queue={}，code={}，message={}",
                    queue, e.getCode(), e.getMessage());
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("MQ 消息消费非预期异常，nack 入 DLQ，queue={}", queue, e);
            channel.basicNack(tag, false, false);
        }
    }
}