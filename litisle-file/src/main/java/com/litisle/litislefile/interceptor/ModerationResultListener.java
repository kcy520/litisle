package com.litisle.litislefile.interceptor;



import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ModerationAppealResultEvent;
import com.litisle.litisleapi.event.moderation.ModerationResultEvent;
import com.litisle.litislefile.enums.SafeFlagEnum;
import com.litisle.litislefile.service.FileService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 审核结果消费者（file 侧）：回写 safe_flag + 违规图隔离/恢复。
 *
 * 【队列职责】
 * - moderation.result：REJECT→quarantine()（物理隔离 + safe_flag=0）；
 *   PASS→safe_flag=1；SUSPICIOUS→safe_flag=3（人审终局前占位态）
 * - moderation.appeal.result：改判 PASS→unquarantine()（移回原前缀 + safe_flag=1）；
 *   维持驳回→无操作（保持隔离）
 *
 * 【幂等】业务幂等覆盖重复投递：quarantine/unquarantine 有前缀幂等闸口，
 * updateSafeFlag 有目标态短路，重投安全，无需额外 eventId SETNX
 *
 * 【ACK 纪律】手动 ACK：消费成功 ack；业务性失败（如文件不存在）warn 后 ack 丢弃
 * （防毒丸消息无限重投）；非预期异常 nack requeue=false 入 DLQ 兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationResultListener {

    private final FileService fileService;

    /** 审核维度：图片（review 侧 ModerationBizTypeEnum biz_type=6，file 侧仅消费此值） */
    private static final int BIZ_TYPE_IMAGE = 6;

    /** 终局判定口径（review 侧 VerdictEnum code） */
    private static final int VERDICT_PASS = 1;
    private static final int VERDICT_REJECT = 2;
    private static final int VERDICT_SUSPICIOUS = 3;

    /**
     * 消费审核结果：回写 safe_flag / 隔离违规图
     */
    @RabbitListener(queues = MqConstant.QUEUE_FILE_MODERATION_RESULT)
    public void onModerationResult(ModerationResultEvent event, Message message, Channel channel)
            throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            handleModerationResult(event);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("审核结果事件消费失败，进 DLQ，eventId={}，bizType={}，bizId={}",
                    event == null ? null : event.getEventId(),
                    event == null ? null : event.getBizType(),
                    event == null ? null : event.getBizId(), e);
            channel.basicNack(tag, false, false);
        }
    }

    /**
     * 消费申诉裁决结果：改判 PASS 时恢复隔离
     */
    @RabbitListener(queues = MqConstant.QUEUE_FILE_MODERATION_APPEAL_RESULT)
    public void onAppealResult(ModerationAppealResultEvent event, Message message, Channel channel)
            throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            handleAppealResult(event);
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("申诉裁决事件消费失败，进 DLQ，eventId={}，bizId={}",
                    event == null ? null : event.getEventId(),
                    event == null ? null : event.getBizId(), e);
            //RabbitMQ 的手动 NACK（否定确认），用在消费消息失败时的兜底处理。
            channel.basicNack(tag, false, false);
//            参数	    值	        含义
//            tag	    deliveryTag	当前消息的唯一投递标识，用来告诉 broker“我说的是哪条消息”
//            multiple	false	    只否定当前这一条消息，不批量处理
//            requeue	false	    不重新入队，直接丢弃或进入 DLQ（死信队列）
        }
    }

    /** 审核结果处理：仅消费图片（bizType=6），verdict 三分态分流 */
    private void handleModerationResult(ModerationResultEvent event) {
        if (event == null || event.getBizType() == null || event.getBizType() != BIZ_TYPE_IMAGE) {
            log.info("非图片审核结果，file 侧忽略，bizType={}",
                    event == null ? null : event.getBizType());
            return;
        }
        String fileId = event.getBizId();
        Integer verdict = event.getVerdict();
        if (verdict == null) {
            log.warn("审核结果事件 verdict 为空，忽略，fileId={}，eventId={}", fileId, event.getEventId());
            return;
        }
        switch (verdict) {
            // REJECT：物理隔离（移入 quarantine/ 前缀）+ safe_flag=0（方法内含幂等闸口）
            case VERDICT_REJECT -> fileService.quarantine(fileId);
            // PASS：回写正常
            case VERDICT_PASS -> fileService.updateSafeFlag(fileId, SafeFlagEnum.NORMAL);
            // SUSPICIOUS：人审终局前占位态
            case VERDICT_SUSPICIOUS -> fileService.updateSafeFlag(fileId, SafeFlagEnum.SUSPICIOUS);
            default -> log.warn("审核结果事件 verdict 非法，忽略，fileId={}，verdict={}", fileId, verdict);
        }
    }

    /** 申诉结果处理：改判 PASS 恢复隔离（移回原前缀 + safe_flag=1）；维持驳回保持隔离 */
    private void handleAppealResult(ModerationAppealResultEvent event) {
        if (event == null || event.getBizType() == null || event.getBizType() != BIZ_TYPE_IMAGE) {
            return;
        }
        if (Boolean.TRUE.equals(event.getAppealPass())) {
            fileService.unquarantine(event.getBizId());
        } else {
            log.info("申诉维持驳回，违规图保持隔离，fileId={}，appealId={}",
                    event.getBizId(), event.getAppealId());
        }
    }
}