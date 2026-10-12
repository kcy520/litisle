package com.litisle.litislereview.mq.publisher;


import com.litisle.commoncore.exception.BizException;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ModerationAppealResultEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 申诉裁决结果事件统一发布器（板块五 5-4.3，appeal.result 出口收口）
 *
 * 【落位说明】对齐 {@link PunishResultPublisher} 落 mq/publisher 包：
 * MQ 出口基建独立成包收敛，judgeAppeal 维持/撤销两分支统一走本发布器（禁另起发布分支防口径漂移）。
 *
 * 【事务顺序铁律（5-4.3）】本地事务提交 → afterCommit 中调用本发布器 → sendCritical 必达发送；
 * 裁决落库失败不发事件（CAS 步骤保证），发布失败转 IllegalStateException → 不 ACK 入 DLQ 兜底
 * （消息不丢，延迟重试；review_task_id 为下游 notification 幂等键，重复投递天然去重）。
 *
 * 【消费端口径】事件仅作触发信号：
 * - file 消费：appealPass=true 时 safe_flag 0→1 + unquarantine 移回原前缀（5-5 对称恢复，已落地）；
 *   appealPass=false 维持驳回无操作（保持隔离）
 * - content/message 消费：allow-list CAS 恢复（article 3→2/4→2、comment 2→1、message 2→1）
 *   ——随对应模块开发回接（5-4.4）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationAppealResultPublisher {

    /** MQ 发送工具（common-mq：sendCritical 必达语义 + traceId MDC 透传） */
    private final MqSendUtil mqSendUtil;

    /**
     * 发布申诉裁决结果事件（appeal.result 单一收口：维持/撤销两分支均走本方法）
     *
     * <p>调用时机由 judgeAppeal 编排保证：afterCommit（事务已提交）阶段调用，本方法不做事务感知；
     * 事件体（bizType/bizId/appealId/appealPass/reviewTaskId）由裁决编排组装，本方法不做二次加工。</p>
     *
     * @param event 申诉裁决结果事件体（reviewTaskId 必带，作 notification 幂等键）
     */
    public void publish(ModerationAppealResultEvent event) {
        // 步骤1：sendCritical 必达发送至 EXCHANGE_MODERATION + ROUTING_MODERATION_APPEAL_RESULT
        //      （common-mq 必达语义：publisher confirm + 失败重试；traceId 随 MqSendUtil MDC 透传）
        // 步骤2：BizException 转 IllegalStateException（对齐 PunishResultPublisher 口径：
        //      BizException 会被消费端按业务性失败 ack 致事件静默丢失，转非预期异常 nack 入 DLQ 兜底）
        try {
            mqSendUtil.sendCritical(MqConstant.EXCHANGE_MODERATION,
                    MqConstant.ROUTING_MODERATION_APPEAL_RESULT, event);
        } catch (BizException e) {
            throw new IllegalStateException("申诉裁决结果事件发送失败，转 DLQ 兜底重试，appealId="
                    + event.getAppealId() + "，reviewTaskId=" + event.getReviewTaskId()
                    + "，appealPass=" + event.getAppealPass(), e);
        }
    }
}
