package com.litisle.litisleai.provider.text;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.metrics.AiMetrics;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 模型通道熔断自检装饰器（板块六 6.2：provider 层运行时自检，机审与 Agent 全通道共用）
 *
 * <p>【职责边界】本类是模型通道装饰链中的熔断收口点：连续失败达阈值判定「确定不可用」，
 * 熔断期内调用直接失败、不再逐条请求模型（机审侧由 review 转人工语义兜底，生成侧抛 80005）；
 * 熔断到期后半开探测一次，成功自动恢复切回，恢复无需人工干预。prompt 组织、计量、预算
 * 均在上游（service / MeteringAgentChatModel），本类只做「放行/拒绝 + 失败计数」。</p>
 *
 * <p>【状态机】CLOSED（正常，统计连续失败）→ OPEN（熔断期，直接拒绝）→ HALF_OPEN（半开，
 * 放行一个探测请求）→ 探测成功回 CLOSED / 探测失败回 OPEN 重新计时。</p>
 *
 * <p>【失败语义】单次失败照常 fail-fast 上抛（走既有重试语义），不吞错不默认放行；
 * 熔断期与半开期的拒绝抛 80005（AI_PROVIDER_UNAVAILABLE），与通道未启用同错误码口径。</p>
 *
 * <p>【观测】ai_guard_state{provider} Gauge 绑定熔断状态：1=正常/半开探测中，0=熔断期
 * （未接 actuator 时注册静默跳过）。</p>
 *
 * <p>【配置】阈值/计数窗口/熔断期时长外置 AiProviderProperties；阈值 ≤0 时本装饰器
 * 直通不熔断（仅保留既有单次失败语义）。</p>
 *
 * <p>【通俗说明】模型通道的"电闸保险丝"：模型服务如果连续挂掉好几次，再一个个请求
 * 硬等只会白耗时间。本类在正常通道外面套了一层闸——连续失败达到阈值就"跳闸"（熔断），
 * 之后一段时间内所有调用直接报"通道不可用"，不再去撞模型；熔断时间一到，放一个
 * "探路"请求试试水：成功就自动合闸恢复正常，失败就重新跳闸继续等。全程自动恢复，
 * 不用人工重启。它只决定"放行还是拒绝 + 记失败次数"，prompt 和记账都不归它管；
 * 阈值配成 ≤0 相当于把保险丝短接，直接直通。</p>
 *
 * @author litisle
 */
@Slf4j
public class CircuitBreakerChatModel implements ChatModel {

    /** 熔断状态：正常（连续失败计数中） */
    private static final int STATE_CLOSED = 0;

    /** 熔断状态：熔断期（直接拒绝调用） */
    private static final int STATE_OPEN = 1;

    /** 熔断状态：半开（放行一个探测请求） */
    private static final int STATE_HALF_OPEN = 2;

    /** 底层模型通道（脱敏装饰链出口） */
    private final ChatModel delegate;

    /** 通道配置（熔断阈值/窗口/熔断期时长 + provider 指标标签） */
    private final AiProviderProperties properties;

    /** 指标打点组件（ai.guard.state Gauge 注册入口） */
    private final AiMetrics metrics;

    /** 状态锁（状态迁移串行化；模型调用在锁外执行，不占用锁等待模型响应） */
    private final Object lock = new Object();

    /** 当前熔断状态（STATE_CLOSED / STATE_OPEN / STATE_HALF_OPEN） */
    private int state = STATE_CLOSED;

    /** 当前计数窗口内的连续失败次数（成功清零；窗口外失败重新计数） */
    private int consecutiveFailures = 0;

    /** 最近一次失败时间戳（窗口判定依据） */
    private long lastFailureAt = 0L;

    /** 进入熔断期的时间戳（半开探测到期判定依据） */
    private long openedAt = 0L;

    /** 半开探测放行标记：true=尚无探测请求占用，下一个获取放行的调用者即探测请求 */
    private boolean probeAllowed = false;

    /**
     * 构造注入装饰链依赖，并注册通道熔断状态 Gauge
     *
     * <p>【通俗说明】除了装好底层通道和配置，还往监控面板注册了一个仪表：
     * 实时显示这个通道的熔断状态（1 = 正常，0 = 熔断中），运维一眼就能看出
     * 通道健不健康。</p>
     */
    public CircuitBreakerChatModel(ChatModel delegate, AiProviderProperties properties, AiMetrics metrics) {
        this.delegate = delegate;
        this.properties = properties;
        this.metrics = metrics;
        // ai_guard_state{provider}：1=正常/半开探测中，0=熔断期（未接 actuator 时注册静默跳过）
        metrics.registerGuardGauge(properties.getProviderName(), () -> !isOpen());
    }

    /**
     * 发起一次模型调用：熔断状态判定 → 放行则委托底层通道 → 按结果更新熔断状态
     *
     * @param request 模型调用请求
     * @return 模型响应（透传底层通道结果）
     * @throws BizException 熔断期/半开期非探测调用被拒绝（80005）；底层调用失败原样上抛
     *
     * <p>【通俗说明】每次调模型前先问闸门"能过吗"（tryAcquire）：不让过就直接报错，
     * 让过才真正去调底层通道——成功就通知闸门"这次成了"（计数清零、可自动恢复），
     * 失败就通知"这次挂了"（连续失败 +1，可能触发跳闸）。异常原样上抛不吞。</p>
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        // 熔断判定：拒绝即抛 80005（机审侧 review 转人工兜底，生成侧明确报错），不逐条白等模型
        tryAcquire();
        try {
            ChatResponse response = delegate.chat(request);
            // 调用成功：恢复正常（半开探测成功即自动切回，无需人工干预）
            onSuccess();
            return response;
        } catch (RuntimeException e) {
            // 调用失败：连续失败计数 +1（半开探测失败重新进入熔断期）；异常原样上抛不吞错
            onFailure();
            throw e;
        }
    }

    /**
     * 熔断状态放行判定：CLOSED 直接放行；OPEN 到期转半开放行首个探测请求、未到期拒绝；
     * HALF_OPEN 仅放行一个探测请求，其余拒绝
     *
     * @throws BizException 熔断期/半开期被拒绝（80005）
     *
     * <p>【通俗说明】闸门放行判定，三种情况：① 正常态 → 直接放行；② 熔断期 →
     * 看时间到了没：没到继续拒（每条白等模型超时太亏），到了就转半开、把当前
     * 这个调用者当探测请求放行；③ 半开期 → 只放第一个抢到名额的探测请求，
     * 其余一律拒，避免一堆请求并发去砸一个还在故障中的模型。</p>
     */
    private void tryAcquire() {
        // 阈值 ≤0：熔断自检停用，直通（保留既有单次失败 fail-fast 语义）
        if (properties.getBreakerFailureThreshold() <= 0) {
            return;
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            switch (state) {
                case STATE_CLOSED:
                    // 正常态直接放行
                    return;
                case STATE_OPEN:
                    if (now - openedAt >= properties.getBreakerOpenMs()) {
                        // 熔断到期 → 转半开：当前调用者即探测请求，立即占用探测名额
                        state = STATE_HALF_OPEN;
                        probeAllowed = false;
                        log.info("模型通道熔断到期转半开，放行探测请求 provider={}", properties.getProviderName());
                    } else {
                        // 熔断期内：直接失败不再逐条请求模型，避免每条白等超时
                        throw breakerReject("AI 模型通道熔断中，请稍后重试");
                    }
                    return;
                case STATE_HALF_OPEN:
                    if (probeAllowed) {
                        // 占用探测名额：本次调用即半开探测请求
                        probeAllowed = false;
                        return;
                    }
                    // 探测请求在途：其余调用直接失败，不并发打模型
                    throw breakerReject("AI 模型通道熔断探测中，请稍后重试");
                default:
                    // 未知状态按熔断拒绝兜底（防御性，正常不可达）
                    throw breakerReject("AI 模型通道状态异常");
            }
        }
    }

    /**
     * 调用成功后的状态迁移：回正常态、连续失败清零、探测名额复位
     *
     * <p>【通俗说明】"成了！"——不管之前是正常还是半开探测，一律回到正常态、
     * 失败计数清零。如果是从熔断/半开恢复过来的，多打一条"已自动恢复"的日志。</p>
     */
    private void onSuccess() {
        synchronized (lock) {
            boolean recovered = state != STATE_CLOSED;
            state = STATE_CLOSED;
            consecutiveFailures = 0;
            probeAllowed = false;
            if (recovered) {
                log.info("模型通道熔断探测成功，自动恢复切回 provider={}", properties.getProviderName());
            }
        }
    }

    /**
     * 调用失败后的状态迁移：半开探测失败 → 重新熔断；正常态按窗口累计连续失败，
     * 达阈值进入熔断期
     *
     * <p>【通俗说明】"挂了！"——分两种情况：如果刚才那笔是半开探测，说明模型还没
     * 好利索，直接重新跳闸再等一轮；如果是正常态的失败，先看上次失败离现在是不是
     * 超出计数窗口（超了说明不是"连续"失败，重新从零数起），然后失败数 +1，
     * 攒够阈值就跳闸进入熔断期。</p>
     */
    private void onFailure() {
        // 阈值 ≤0：停用熔断，不计数
        if (properties.getBreakerFailureThreshold() <= 0) {
            return;
        }
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (state == STATE_HALF_OPEN) {
                // 半开探测失败：判定仍不可用，重新进入熔断期等待下一轮探测
                tripOpen(now, "半开探测失败");
                return;
            }
            // 计数窗口外的失败重新计数（防偶发失败跨窗口累积成「连续失败」）
            if (lastFailureAt > 0 && now - lastFailureAt > properties.getBreakerWindowMs()) {
                consecutiveFailures = 0;
            }
            consecutiveFailures++;
            lastFailureAt = now;
            if (consecutiveFailures >= properties.getBreakerFailureThreshold()) {
                tripOpen(now, "连续失败达阈值 " + consecutiveFailures);
            }
        }
    }

    /**
     * 进入熔断期：状态置 OPEN、记录熔断起始时间、复位探测名额
     *
     * @param now   当前时间戳（熔断起始时间）
     * @param cause 进入熔断的原因（日志用）
     *
     * <p>【通俗说明】执行"跳闸"动作：状态切到熔断、记下跳闸时间（后面据此判断
     * 什么时候放探测请求）、清掉探测名额，并打一条带原因的警告日志。</p>
     */
    private void tripOpen(long now, String cause) {
        state = STATE_OPEN;
        openedAt = now;
        probeAllowed = false;
        log.warn("模型通道进入熔断期 provider={} cause={} 熔断时长Ms={}",
                properties.getProviderName(), cause, properties.getBreakerOpenMs());
    }

    /**
     * 构建熔断拒绝异常（80005，与通道未启用同错误码口径）
     *
     * @param message 拒绝说明
     * @return BizException（AI_PROVIDER_UNAVAILABLE）
     *
     * <p>【通俗说明】造一个"通道不可用"的标准异常用于拒绝调用——错误码与通道未启用
     * 保持一致（80005），上层不需要区分"没开"和"熔断中"两种不可用。</p>
     */
    private BizException breakerReject(String message) {
        log.warn("模型通道调用被熔断拒绝 provider={} state={} message={}",
                properties.getProviderName(), state, message);
        return new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, message);
    }

    /**
     * 判断当前是否处于熔断期（ai_guard_state Gauge 数据源）
     *
     * @return true=熔断期（Gauge 显示 0）；false=正常/半开探测中（Gauge 显示 1）
     *
     * <p>【通俗说明】给监控仪表读数用的小方法：现在是不是熔断期。半开探测阶段
     * 算"快好了"，显示为正常。</p>
     */
    private boolean isOpen() {
        synchronized (lock) {
            return state == STATE_OPEN;
        }
    }
}
