package com.litisle.commonmq.consume;


import com.litisle.commoncore.exception.BizException;
import com.litisle.commonmq.send.MqSendUtil;
import com.rabbitmq.client.AMQP;
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


// ACK = acknowledgement（确认回执） ——消费者告诉 RabbitMQ“这条消息我处理完了（或没处理完）”，broker 据此决定 删不删这条消息 。它是 MQ 可靠性机制的基石。
// RabbitMQ 推消息给消费者后，消息并不能立刻删——万一消费者处理到一半进程崩了，消息就丢了。所以 broker 会把已投递的消息标记为“ Unacked（待确认） ”，一直等消费者的回执：
// Broker ──投递──► 消费者            消息状态：Unacked（挂在队列里不删）
//         ◄──ACK────                消息状态：删除，任务完成
//         ◄──NACK/reject──          消息状态：按参数决定重投或进死信

// 模式	            行为	                                    风险
// 自动 ACK（auto）	消息一投递到消费者就立刻标记删除，不等业务处理	业务处理中途崩了 → 消息已删 → 丢失
// 手动 ACK（manual）	消费者业务代码执行成功后，显式调用 ack 才删除	必须记得 ack——忘记 ack 消息会一直堆积在 Unacked 状态

// ACK 是消费者给 broker 的“处理完成回执”；手动 ACK = 把“什么时候算处理完成”的决定权交给业务代码 ——成功才删、暂时失败退避重试、永久失败死信留痕。它和“自动 ACK 一投递就删”相比，牺牲了一点代码复杂度（必须处理每个分支），换来的是 消息不丢 这一可靠性底线。面试答 MQ 可靠性时，通常就是从“生产端确认 + broker 持久化 + 消费端手动 ACK”三板斧展开，手动 ACK 是其中消费端那一板。
// 回执”不是返回值，也不是 HTTP 响应，而是 一条通过 TCP 连接发回 broker 的 AMQP 协议帧 。

// ACK 就是签收回执 ：broker 每送一条消息都带一个单号，你处理完后拿单号喊一嗓子“签收了”，broker 才销单；喊“拒签”就去死信队列。broker 没等到你喊话之前，消息永远不删——这就是“消息不丢”的全部秘密。
// 回执 = 复用启动时已建立的 TCP 长连接 ，往 broker 发一条只含“单号 + 签收/拒签”的极简通知。它不建新连接、不带失败详情；broker 收到签收就删消息，收到拒签就转死信，收不到就等连接断了重投。

    private MqAckHelper() {
    }

    /**
     * 延迟重投发布器（板块三 3-8.1 消费端指数退避重试的「重投动作」回调）。
     *
     * <p>由业务消费者闭包实现：递增事件体 retryCount（与消息头双写对账，DLQ 侧 3-6 按事件体
     * 写回 review_task.retry_count）→ MqSendUtil.sendDelayed 发往延迟交换机。
     * MqAckHelper 只负责「计数判断 + 退避计算 + ack/nack 分流」，不感知事件类型与路由细节。</p>
     */
    @FunctionalInterface
    public interface DelayedRetryPublisher {

        /**
         * 执行一次延迟重投
         *
         * @param newRetryCount 递增后的重试次数（消费方须同步写入事件体 retryCount，对账口径）
         * @param delayMs       指数退避延迟毫秒数（baseDelayMs × 2^n）
         */
        void republish(int newRetryCount, long delayMs);
    }

    /**
     * 执行业务逻辑并按「重试感知 ACK 纪律」确认消息（板块三 3-8.1，在 {@link #executeWithAck}
     * 基础上为非预期异常增加指数退避延迟重试编排）。
     *
     * 在 RabbitMQ 消费失败时，按“可重试 vs 不可重试”区分处理：可预期业务失败直接丢弃，
     * 环境类/临时异常走指数退避延迟重试，重试耗尽或重投失败则进 DLQ 转人工，绝不静默丢消息。
     *
     * <p>【重试口径】与 executeWithAck 的差异仅在非预期异常分支：</p>
     * <ul>
     *   <li>未耗尽（当前 retryCount &lt; maxRetryCount）：计算指数退避延迟 = baseDelayMs × 2^retryCount，
     *       调 retryPublisher 延迟重投（x-delayed 插件按 x-delay 头延迟后按原路由键落回主队列），
     *       成功后 ack 原消息——原消息从主队列移除，消息进入延迟等待，不阻塞主队列；</li>
     *   <li>重投失败：重投丢失会导致消息既不在主队列也不在 DLQ，因此立即 nack 入 DLQ 兑底，绝不静默丢失；</li>
     *   <li>已耗尽（当前 retryCount ≥ maxRetryCount）：nack(requeue=false) 经 DLX 入 DLQ，
     *       由 3-6 死信消费转人审（绝不自动放行）。</li>
     * </ul>
     *
     * <p>【毒丸防护不变】BizException（业务性失败）仍 ack 丢弃；Jackson 反序列化失败抛的
     * MessageConversionException 仍落本方法 catch (Exception) 分支参与重试——
     * 其重试至多 maxRetryCount 次后同样入 DLQ 停止重投，不破坏毒丸防护。</p>
     *
     * @param queue          队列名（日志定位用）
     * @param message        原始消息（取 deliveryTag / retryCount 消息头）
     * @param channel        消费通道
     * @param maxRetryCount  重试上限（如 ModerationProperties.Retry.maxCount）
     * @param baseDelayMs    指数退避基数毫秒（如 ModerationProperties.Retry.baseDelayMs）
     * @param retryPublisher 延迟重投回调（调用方闭包实现，见 DelayedRetryPublisher）
     * @param business       业务逻辑（BizException 即按「业务性失败」处理）
     * @throws IOException ack/nack IO 异常向上抛出，交由容器按连接异常处理
     */
    public static void executeWithRetry(String queue, Message message, Channel channel,
                                        int maxRetryCount, long baseDelayMs,
                                        DelayedRetryPublisher retryPublisher, Runnable business)
            throws IOException {
        // 步骤1：取当前消息的投递标识（deliveryTag）
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            // 步骤2：业务成功 → ack
            business.run();
            channel.basicAck(tag, false);
        } catch (BizException e) {
            // 步骤3：业务性失败——可预期、重试无意义（任务缺失/hash 错配等），warn 后 ack 丢弃防毒丸
            log.warn("MQ 消息业务性失败，确认丢弃（防毒丸），queue={}，code={}，message={}",
                    queue, e.getCode(), e.getMessage());
            channel.basicAck(tag, false);
        } catch (Exception e) {
            // 步骤4：非预期异常（环境类故障/反序列化失败）→ 指数退避延迟重试编排：
            // 从消息头读当前重试次数（首次消费无头按 0），与上限比较分流
            int currentRetry = readRetryCount(message);
            if (currentRetry < maxRetryCount) {
                // 步骤4.1：未耗尽 → 指数退避：第 n 次重试延迟 = baseDelayMs × 2^(n-1)
                //（首次重试 currentRetry=0 → delay=base，依次 30s/60s/120s/240s/480s）
                int newRetryCount = currentRetry + 1;
                long delayMs = baseDelayMs * (1L << currentRetry);
                try {
                    // 步骤4.2：延迟重投（事件体 retryCount 递增由调用方闭包内完成，与消息头双写对账）
                    retryPublisher.republish(newRetryCount, delayMs);
                    log.warn("MQ 消息消费失败，已按指数退避延迟重投，queue={}，retry={}/{}，delayMs={}ms，cause={}",
                            queue, newRetryCount, maxRetryCount, delayMs, e.getMessage());
                    // 步骤4.3：重投成功后 ack 原消息（原消息出列进入延迟等待，主队列不堆积）
                    channel.basicAck(tag, false);
                    return;
                } catch (Exception republishEx) {
                    // 步骤4.4：重投失败 → 消息即将三无（不在主队列/DLQ/延迟交换机），nack 入 DLQ 兑底不静默丢失
                    log.error("MQ 消息延迟重投失败，nack 入 DLQ 兑底，queue={}", queue, republishEx);
                }
            } else {
                // 步骤4.5：重试耗尽 → error 留痕后 nack 入 DLQ，由 3-6 死信消费转人审（绝不自动放行）
                log.error("MQ 消息重试耗尽（retryCount={}≥max={}），nack 入 DLQ 转人审，queue={}",
                        currentRetry, maxRetryCount, queue, e);
            }
            channel.basicNack(tag, false, false);
        }
        // 步骤5：ack/nack 的 IOException 语义与 executeWithAck 对齐（见其类头说明）
    }

    /**
     * 从消息头读取当前重试次数（{@link MqSendUtil#HEADER_RETRY_COUNT}，双写对账的消息头侧）
     *
     * @param message 原始消息
     * @return 重试次数（消息头缺失/类型不符按 0——首次消费无头，消费方闭包重投时双写补齐）
     */
    private static int readRetryCount(Message message) {
        Object value = message.getMessageProperties().getHeader(MqSendUtil.HEADER_RETRY_COUNT);
        return value instanceof Number number ? number.intValue() : 0;
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