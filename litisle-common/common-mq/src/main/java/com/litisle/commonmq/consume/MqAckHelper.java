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
 * 【毒丸防护·隐式依赖路径】（⚠️ 改动本类 catch 结构前必读）
 * Jackson2JsonMessageConverter 反序列化失败时抛的是 MessageConversionException
 * （Spring AMQP 的 RuntimeException，**不是** BizException，与本项目 BizException 无继承关系），
 * 因此它必然落入 {@link #executeWithAck} 的 catch (Exception) 分支 → nack(requeue=false) → DLQ，
 * 「永远无法反序列化的毒丸消息」靠这条隐式路径入 DLQ 停止重投。
 *
 * ❌ 禁止的改动（都会破坏毒丸防护）：
 * - 把 catch (BizException) 改宽为 catch (Exception) 后统一 ack 丢弃：环境类故障
 *   （DB 超时等重试可成功的消息）会被静默丢数据；
 * - 把 catch (Exception) 分支的 nack 改为 requeue=true：毒丸消息无限重投打满队列；
 * - 在业务 lambda 内部 try-catch 吞掉 MessageConversionException：等于绕过 DLQ 兜底。
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
        // 步骤1：取当前消息的投递标识（deliveryTag）——broker 侧唯一投递序号，
        // ack/nack 都靠它告诉 broker「确认的是哪条消息」
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            // 步骤2：执行业务逻辑（lambda 由调用方传入，本类不感知具体业务）；
            // 业务成功 → 手动确认（multiple=false 仅确认当前这一条，不批量）
            business.run();
            channel.basicAck(tag, false);//“这条消息我已经成功消费了，RabbitMQ 你可以把它从队列里删掉了。”
        } catch (BizException e) {
            // 步骤3：业务性失败分支——BizException 语义为「业务校验不通过」（可预期、重试无意义，
            // 如目标文件不存在/状态不满足），warn 后 ack 丢弃而非重投：
            // 若 nack 重投，这条「注定失败」的消息会无限循环占用队列（毒丸）
            log.warn("MQ 消息业务性失败，确认丢弃（防毒丸），queue={}，code={}，message={}",
                    queue, e.getCode(), e.getMessage());
            channel.basicAck(tag, false);
        } catch (Exception e) {
            // 步骤4：非预期异常分支——环境类故障（DB 超时/网络抖动等，重试可能成功），
            // 以及 Jackson 反序列化失败的 MessageConversionException（毒丸，见类头【毒丸防护·隐式依赖路径】），
            // error 记录完整堆栈后 nack(requeue=false)：
            //   参数1 tag：否定当前这条消息
            //   参数2 multiple=false：不批量否定
            //   参数3 requeue=false：不重新入队 → 队列配置的 DLX（死信交换机）将其路由进 DLQ 兜底，
            //        避免无限重投；后续人工/对账介入处理
            log.error("MQ 消息消费非预期异常，nack 入 DLQ，queue={}", queue, e);
            channel.basicNack(tag, false, false);
        }
        // 步骤5：ack/nack 本身的 IOException 语义（与单测 MqAckHelperTest 边界用例对齐）：
        // ack 在 try 块内，其 IOException 会被 catch (Exception) 捕获兑底转 nack（channel 已死时
        // nack 同样抛 IOException 向上冒泡）；nack 的 IOException 不在捕获范围内，直接向上抛出
        // 交由 RabbitMQ 容器按连接异常处理（channel 出错时 broker 会重投未确认消息，天然幂等重试入口）
    }
}