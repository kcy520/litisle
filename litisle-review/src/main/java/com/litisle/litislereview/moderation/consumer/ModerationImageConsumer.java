package com.litisle.litislereview.moderation.consumer;

import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litislereview.moderation.service.ModerationImageService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 图片送审消费者（板块三 3-2，§4.2.2 / §2.1）——骨架，暂只留注释、未实现具体代码
 *
 * 【落位】consumer 包。【职责】监听 moderation.image.submit 队列（file 上传预审发布），
 * 只做消费编排（traceId 恢复 + ACK 模板），业务全部委托 {@link ModerationImageService#process(ImageSubmitEvent)}。
 *
 * 【事件体】ImageSubmitEvent：bizType=6 / bizId（图片文件标识，file 侧 setBizId(meta.getFileId())）/ scene / contentHash；
 * 事件体不含图片地址——图片由消费端走 FileClient.getContent(fileId) 内部字节流回查（严禁按用户 URL 抓取，SSRF 防线）。
 *
 * 【场景】scene=IMAGE→单图审；scene=ARTICLE_IMG_RE_REVIEW→按文章 bizId 经 ContentClient 反查配图列表逐张送审（聚合见 Service）。
 *
 * 【前提（待补，本步暂不动）】需先在 MqConstant 增加 QUEUE_MODERATION_IMAGE_SUBMIT / QUEUE_MODERATION_IMAGE_SUBMIT_DLQ /
 * ROUTING_MODERATION_IMAGE_SUBMIT_DLQ，并在 ReviewRabbitConfig 声明同名队列与 DLX 绑定（参数须与 file 侧一致，防 PRECONDITION_FAILED）。
 *
 * 【注意事项】图片场景（IMAGE/ARTICLE_IMG_RE_REVIEW）不走 DFA（DfaScenes 归一为 null）；逐图送 SPI、不逐图另建
 * biz_type=6 任务；Semaphore/超时已在适配器（板块一），消费端只做异常→延迟重试编排。
 *
 * 【ACK 纪律】（复用 common-mq MqAckHelper，禁止手写 try-catch-ack-nack）
 * - 业务成功 → ack；业务性失败（BizException）→ warn 后 ack 丢弃（防毒丸）；
 * - 非预期异常（取图失败/DB 超时/发布失败）→ error 后 nack(requeue=false) 经 DLX 入 DLQ。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationImageConsumer {

    /** 图片审核编排服务（3-2 业务逻辑载体） */
    private final ModerationImageService moderationImageService;

    /**
     * 消费图片送审消息（消费编排模板；ACK 纪律与 traceId 恢复收口 common-mq）。
     *
     * @param event   图片送审事件（litisle-api 契约）
     * @param message 原始消息（取 deliveryTag / traceId Header）
     * @param channel 消费通道（ack/nack）
     */
    @RabbitListener(queues = MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT)
    public void onImageSubmit(ImageSubmitEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：从消息 Header 恢复 traceId 到 MDC（消费线程日志自动带链路 ID）
        MqTraceSupport.restore(message);
        try {
            // 步骤2：手动 ACK 模板收口 common-mq（禁止手写 try-catch-ack-nack）：
            //        成功 ack / BizException ack 丢弃（防毒丸）/ 其他异常 nack(requeue=false) 经 DLX 入 DLQ
            // 步骤3：委托图片审核编排服务（场景分流/取图走 FileClient 字节流/缓存查重/单图落库/复审聚合/发 result 事件）
            MqAckHelper.executeWithAck(MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT, message, channel,
                    () -> moderationImageService.process(event));
        } finally {
            // 步骤4：清理 MDC（消费线程由容器复用，不清除会串扰下一条消息的日志链路 ID）
            MqTraceSupport.clear();
        }
    }
}