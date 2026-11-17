package com.litisle.litisleai.agent;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.service.AiUsageRecordService;
import com.litisle.litisleai.service.support.AiUsageDetail;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

/**
 * Agent 计量装饰模型（板块二 2.1/2.2：AiServices 内部循环的计量与轮次上限收口点）
 *
 * <p>【职责边界】本类是 AiServices 与底层模型通道之间的装饰器：每轮模型调用按
 * requestId+callSeq 落计量明细（Agent 多轮按轮递增，1.3 口径）、打点指标、强制执行
 * Agent 循环上限；prompt 组织与意图路由在 service 层。工具失败不经过本类
 * （工具方法内部兜底为观察文本回喂，不中断会话）。</p>
 *
 * <p>【循环上限】Agent 阶段（agentPhase=true）按已消耗轮次判定，达到上限直接抛
 * AI_AGENT_LOOP_EXCEEDED（不发起本轮调用、不产生成本），由 service 降级为固定管道直连；
 * 降级直连阶段 leaveAgentPhase() 后解除约束，callSeq 继续累计保证计量连续。</p>
 *
 * <p>【计量承诺】每轮调用成功/失败均落明细（失败即无成本防护）；无上下文（AgentCallContext
 * 缺席）时直通不计量——仅兜底路径允许，正常链路必须 begin() 开启上下文。</p>
 *
 * @author litisle
 */
@Slf4j
public class MeteringAgentChatModel implements ChatModel {

    /** 底层模型通道（出站脱敏装饰链的出口） */
    private final ChatModel delegate;

    /** 计量明细服务 */
    private final AiUsageRecordService usageRecordService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** 通道配置（provider 标签 / model 版本快照） */
    private final AiProviderProperties providerProperties;

    /** Agent 配置（循环上限） */
    private final AiAgentProperties agentProperties;

    /**
     * 构造注入装饰链（AiServices → 本装饰器 → 脱敏装饰器 → SDK）
     */
    public MeteringAgentChatModel(ChatModel delegate,
                                  AiUsageRecordService usageRecordService,
                                  AiMetrics metrics,
                                  AiProviderProperties providerProperties,
                                  AiAgentProperties agentProperties) {
        this.delegate = delegate;
        this.usageRecordService = usageRecordService;
        this.metrics = metrics;
        this.providerProperties = providerProperties;
        this.agentProperties = agentProperties;
    }

    /**
     * 代理一轮模型调用：轮次上限判定 → 委托调用 → 按轮计量留痕
     *
     * @param request 模型调用请求（AiServices 组装的消息列表 + 工具规格）
     * @return 模型响应
     * @throws BizException Agent 循环超限（80007）、模型调用失败（80005）
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        AgentCallContext context = AgentCallContext.get();
        // 无上下文兜底直通：正常链路必须经 begin() 开启上下文（计量与上限均依赖它）
        if (context == null) {
            log.warn("Agent 调用缺少上下文，跳过计量直通（兜底路径）");
            return delegate.chat(request);
        }
        // 循环上限判定：达到上限不发起本轮调用（零成本），fail-fast 由 service 降级
        if (context.isAgentPhase() && context.currentCallSeq() >= agentProperties.getMaxLoopRounds()) {
            log.warn("Agent 循环达到上限 {} 轮，终止推理 requestId={} purpose={} 已耗轮次={}",
                    agentProperties.getMaxLoopRounds(), context.getRequestId(),
                    context.getPurpose().getCode(), context.currentCallSeq());
            throw new BizException(ErrorCode.AI_AGENT_LOOP_EXCEEDED);
        }
        // 本轮调用序号（requestId+callSeq 联合唯一落明细）
        int callSeq = context.nextCallSeq();    //「当前已消耗次数」就是`AgentCallContext` 内的一个内存计数器`callSeq` ，按 每次模型调用轮次 递增，不查数据库、纯请求内累计。
        long start = System.currentTimeMillis();
        try {
            ChatResponse response = delegate.chat(request);
            // token 数可能为 null（供应商未返回），计量侧按 0 兜底
            Integer tokenIn = response.tokenUsage() == null ? null : response.tokenUsage().inputTokenCount();
            Integer tokenOut = response.tokenUsage() == null ? null : response.tokenUsage().outputTokenCount();
            // 成功留痕：Agent 阶段降级标记恒 0（降级直连的明细由直连链路落，degraded=1）
            usageRecordService.recordSuccess(AiUsageDetail.builder()
                    .requestId(context.getRequestId())
                    .callSeq(callSeq)
                    .userId(context.getUserId())
                    .purpose(context.getPurpose())
                    .scene(context.getScene())
                    .modelVersion(providerProperties.getModel())
                    .tokenIn(tokenIn)
                    .tokenOut(tokenOut)
                    .degraded(0)
                    .occurredAt(LocalDateTime.now())
                    .build());
            metrics.recordCall(context.getPurpose().getCode(), providerProperties.getProviderName(),
                    AiMetrics.RESULT_SUCCESS);
            metrics.recordLatency(context.getPurpose().getCode(), System.currentTimeMillis() - start);
            // token 指标由 usageRecordService.recordSuccess 统一打点，此处不重复计数
            // 记录最近一轮 token 消耗（终答消息落库 ai_message.prompt/completion_tokens 对账用）
            context.recordLastTokens(tokenIn, tokenOut);
            return response;
        } catch (BizException e) {
            // 本类自身的明确异常（循环超限不会到这——上限判定在调用前；此处为兜底）直接透出
            recordFailAndMetrics(context, callSeq, start);
            throw e;
        } catch (Exception e) {
            // SDK/网络异常 fail-fast：失败轮次也留明细（失败即无成本防护），异常包装后上抛
            recordFailAndMetrics(context, callSeq, start);
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "AI 模型调用失败", e);
        }
    }

    /**
     * 失败留痕 + 指标打点（失败轮次不阻断异常上抛，只补计量记录）
     *
     * @param context 调用上下文
     * @param callSeq 本轮序号
     * @param start   调用起始时间戳
     */
    private void recordFailAndMetrics(AgentCallContext context, int callSeq, long start) {
        usageRecordService.recordFail(AiUsageDetail.builder()
                .requestId(context.getRequestId())
                .callSeq(callSeq)
                .userId(context.getUserId())
                .purpose(context.getPurpose())
                .scene(context.getScene())
                .modelVersion(providerProperties.getModel())
                .tokenIn(null)
                .tokenOut(null)
                .degraded(0)
                .occurredAt(LocalDateTime.now())
                .build());
        metrics.recordCall(context.getPurpose().getCode(), providerProperties.getProviderName(),
                AiMetrics.RESULT_FAIL);
        metrics.recordLatency(context.getPurpose().getCode(), System.currentTimeMillis() - start);
    }
}
