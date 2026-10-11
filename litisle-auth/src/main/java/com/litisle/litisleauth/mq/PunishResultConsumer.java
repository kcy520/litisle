package com.litisle.litisleauth.mq;


import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.commonmq.consume.MqAckHelper;
import com.litisle.commonmq.consume.MqTraceSupport;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.dto.moderation.PunishStatusDTO;
import com.litisle.litisleapi.event.moderation.PunishResultEvent;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.rabbitmq.client.Channel;
import java.io.IOException;

/**
 * 处罚结果事件消费端（临时清单 B1/B2，§4.2.3/§4.5.2）
 *
 *【实现说明】重试参数外置可调（litisle.moderation.punish.retry.max-count / base-delay-ms），
 * 默认 5 次 / 30s 基数指数退避（30s/60s/120s/240s/480s）
 *
 * 【消费编排（B1，5-2.2）】
 * 步骤1：MqTraceSupport.restore(message)——Header 恢复 traceId 到 MDC
 * 步骤2：经 Feign GET /api/review/punish/status 按 userId 拉权威态
 *        （事件仅作触发信号：无论 op=1/2 一律回查覆盖本地，幂等且与到达顺序无关；
 *        punishLogId 仅排障，严禁作版本门控——撤销就地改同一行、id 不变不单调）
 * 步骤3：PunishSnapshotService 覆盖本地禁言快照（撤销时全空快照=同步解封，B3）
 * 步骤4：action=2-封禁 → Sa-Token kickout 强制下线该用户全部会话（B2，5-2.3）：
 *        StpUtil.logout(userId)（登录态即本服务会话，与 DeviceSessionService 注销语义对齐）
 *
 * 【ACK 纪律】复用 common-mq MqAckHelper 模板（禁止手写 try-catch-ack-nack）：
 * - 业务成功 → ack
 * - 回查失败（REMOTE_SERVICE_ERROR 穿透）→ 指数退避延迟重试（漏封是安全风险，
 *   禁静默吞错；耗尽 nack 入 DLQ 由 5-3.2 双向对账 sweeper 兜底）
 * - 事件体解析失败 → warn 后 ack 丢弃（防毒丸；权威态以回查为准，丢事件不丢正确性，
 *   极端场景由对账 sweeper 正向补漏 kickout 收敛）
 *
 * 【串行消费】（B4 已落地，见 PunishConsumeConfig）：队列固定单消费者；扩容按 userId
 * 一致性哈希路由——「拉取→覆盖写入」可乱序，「不依赖到达顺序」仅在串行下成立
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PunishResultConsumer {

    /** 重试上限（外置可调，耗尽 nack 入 DLQ 由双向对账 sweeper 兑底） */
    @Value("${litisle.moderation.punish.retry.max-count:5}")
    private int retryMaxCount;

    /** 指数退避基数毫秒（外置可调：baseDelayMs × 2^n） */
    @Value("${litisle.moderation.punish.retry.base-delay-ms:30000}")
    private long retryBaseDelayMs;

    /** 审核服务 Feign 客户端（降级 fail-fast，回查失败转重试分支） */
    private final com.litisle.litisleapi.feign.ReviewClient reviewClient;

    /** 本地禁言快照维护（B3，覆盖式写入） */
    private final com.litisle.litisleauth.service.PunishSnapshotService punishSnapshotService;

    /** MQ 发送工具（延迟重投经 EXCHANGE_MODERATION_DELAY x-delayed 交换机） */
    private final MqSendUtil mqSendUtil;

    /**
     * 消费处罚结果事件（B1 权威态覆盖 + B2 封禁即时下线）
     *
     * <p>ACK 编排按 MqAckHelper.executeWithRetry 模板：业务成功 ack；回查失败（非预期异常）
     * 指数退避延迟重试，耗尽 nack 入 DLQ；Jackson 反序列化失败同样走重试至耗尽入 DLQ（防毒丸）</p>
     *
     * @param event   处罚结果事件体（PunishResultEvent JSON）
     * @param message 原始消息（deliveryTag / traceId / retryCount Header）
     * @param channel 消费通道
     */
    @RabbitListener(queues = MqConstant.QUEUE_AUTH_MODERATION_PUNISH,
            containerFactory = "punishListenerContainerFactory")
    public void onPunishResult(PunishResultEvent event, Message message, Channel channel) throws IOException {
        // 步骤1：Header 恢复 traceId 到 MDC（MQ 消费线程非 HTTP 线程，链路需显式恢复，finally 清理防串扰）
        MqTraceSupport.restore(message);
        try {
            // 步骤2：重试感知 ACK 模板执行业务（回查失败→指数退避延迟重投；耗尽→nack 入 DLQ）
            MqAckHelper.executeWithRetry(MqConstant.QUEUE_AUTH_MODERATION_PUNISH, message, channel,
                    retryMaxCount, retryBaseDelayMs,
                    // 延迟重投闭包：retryCount 与消息头双写递增（事件体留痕，无 review_task 承接仅对账用）；
                    // 经延迟交换机按原路由键落回主队列，拓扑与 review 侧 3-8.1 对齐
                    (newRetryCount, delayMs) -> {
                        event.setRetryCount(newRetryCount);
                        mqSendUtil.sendDelayed(MqConstant.EXCHANGE_MODERATION_DELAY,
                                MqConstant.ROUTING_MODERATION_PUNISH, event, delayMs, newRetryCount);
                    },
                    () -> handle(event));
        } finally {
            // 步骤3：清理 MDC，防消费线程池复用 traceId 串扰（与 MqTraceSupport 类注纪律一致）
            MqTraceSupport.clear();
        }
    }

    /**
     * 业务编排：回查权威态 → 覆盖本地快照（B3）→ 封禁即时下线（B2）
     *
     * <p>【异常语义】回查失败抛 IllegalStateException 走非预期分支（重试/入 DLQ）——
     * 降级工厂抛的 BizException 会被 MqAckHelper 按「业务性失败」ack 丢弃，
     * 静默吞错会致漏封（安全风险），故必须转非预期异常（对齐 review 侧发布器同款处理）</p>
     */
    private void handle(PunishResultEvent event) {
        // 步骤1：事件体闸门——userId 必带（事件仅触发信号，缺字段说明发送方 Schema 漂移，重试无意义）
        if (event == null || event.getUserId() == null) {
            log.warn("处罚结果事件体缺失 userId，丢弃（防毒丸），eventId={}", event == null ? null : event.getEventId());
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        Long userId = event.getUserId();
        // 步骤2：Feign 回查权威态——无论 op=1/2 一律回查覆盖本地（幂等且与到达顺序无关）；
        // punishLogId 仅排障，严禁作版本门控（撤销就地改同一行、id 不变不单调）
        Result<PunishStatusDTO> result;
        try {
            result = reviewClient.getPunishStatus(userId);
        } catch (BizException e) {
            // 降级工厂 fail-fast 抛 BizException——转非预期异常走重试/DLQ，禁静默吞错致漏封
            throw new IllegalStateException("处罚权威态回查失败，转重试/DLQ，userId=" + userId, e);
        }
        // 步骤3：回查结果闸门——业务失败/空载荷同样转重试（宁多试不漏封）
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new IllegalStateException("处罚权威态回查返回异常，转重试/DLQ，userId=" + userId
                    + "，msg=" + (result == null ? "null" : result.getMessage()));
        }
        // 步骤4：覆盖本地快照（B3）——撤销=全空快照覆盖=同步解封，顺序无关幂等
        punishSnapshotService.overwriteFromAuthority(result.getData());
        // 步骤5：封禁即时下线（B2，5-2.3）——以回查权威态 banned 为准（非事件 action，双保险防撤销事件误踢）；
        // Sa-Token 会话存储共享 Redis（与 user 模块同库），logout 踢出该用户全部会话；幂等重复执行无害
        if (Boolean.TRUE.equals(result.getData().getBanned())) {
            StpUtil.logout(userId);
            log.info("封禁即时下线完成（kickout 全部会话），userId={}，eventId={}，action={}",
                    userId, event.getEventId(), event.getAction());
        }
    }
}
