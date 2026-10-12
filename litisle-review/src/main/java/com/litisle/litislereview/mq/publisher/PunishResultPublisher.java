package com.litisle.litislereview.mq.publisher;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.PunishResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 处罚结果事件统一发布器（板块五 5-2.1，punish.result 出口收口）
 *
 * 【落位说明】对齐 {@link ModerationResultPublisher} 落 mq/publisher 包：
 * MQ 出口基建独立成包收敛，被机审处罚闸口（MachineRejectPunishGate 宿主注入的 eventSender）、
 * 后续人审 decideTask 封禁联动（禁另起发布分支，防口径漂移）共用。
 *
 * 【事务顺序铁律（5-2.1）】本地事务提交 → afterCommit 中调用本发布器 → sendCritical 必达发送；
 * 落库失败不发事件（闸口步骤5保证），发布失败转 IllegalStateException → 消费端不 ACK 入 DLQ 兑底
 * （消息不丢，延迟重试走 hash 幂等闭环）。
 *
 * 【消费端口径】事件仅作触发信号：auth/user 消费后经 Feign 回查 punish 权威态覆盖本地快照，
 * punishLogId 仅排障、严禁作版本门控（撤销就地改同一行 id 不变）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PunishResultPublisher {

    /** MQ 发送工具（common-mq：sendCritical 必达语义 + traceId MDC 透传） */
    private final MqSendUtil mqSendUtil;

    /**
     * 发布处罚结果事件（punish.result 单一收口：机审 action=3 联动与人审封禁联动均走本方法）
     *
     * <p>调用时机由契约层闸口保证：afterCommit（事务已提交）阶段调用，本方法不做事务感知；
     * 事件体组装口径（action/op/punishLogId/frozenUntil）由闸口收口，本方法不做二次加工。</p>
     *
     * @param event 处罚结果事件体（闸口组装：action=3-内容下架、op=1-生效、frozenUntil=null）
     */
    public void publish(PunishResultEvent event) {
        // sendCritical 必达语义：confirm 成功才返回；BizException 转非预期分支异常——
        // 对齐 ModerationResultPublisher 口径：BizException 会被按业务性失败 ack 致事件静默丢失，
        // 转 IllegalStateException → nack 入 DLQ 兑底（消息不丢，延迟重试）
        try {
            mqSendUtil.sendCritical(MqConstant.EXCHANGE_MODERATION, MqConstant.ROUTING_MODERATION_PUNISH, event);
        } catch (BizException e) {
            throw new IllegalStateException("处罚结果事件发布失败，转 DLQ 兑底，punishLogId=" + event.getPunishLogId()
                    + "，userId=" + event.getUserId() + "，action=" + event.getAction() + "，op=" + event.getOp(), e);
        }
    }
}
