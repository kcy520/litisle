package com.litisle.litisleai.agent;

import com.litisle.litisleapi.dto.ai.AiPurposeEnum;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次生成请求的 Agent 调用上下文（计量幂等键与轮次计数的请求内载体）
 *
 * <p>【口径】requestId 由业务侧生成、重试复用同一键；callSeq 为同一请求内第 N 次模型
 * 调用序号（Agent 多轮按轮递增，降级直连延续计数）——requestId+callSeq 联合唯一落
 * 计量明细（1.3 口径）。ThreadLocal 承载：AiServices 工具循环在同一线程内同步执行。</p>
 *
 * <p>【生命周期】service 调 Agent 前 begin()，finally 中 clear()，防线程池串号。</p>
 *
 * @author litisle
 */
public final class AgentCallContext {

    /** 线程内上下文持有器（Agent 循环与工具执行同线程） */
    private static final ThreadLocal<AgentCallContext> HOLDER = new ThreadLocal<>();

    /** 请求幂等键（业务侧生成，重试复用） */
    private final String requestId;

    /** 用途打标（功能入口预传入，Agent 循环继承——P6 定档） */
    private final AiPurposeEnum purpose;

    /** 发起用户 ID（用户级配额与计量归属） */
    private final Long userId;

    /** 业务场景快照（可空） */
    private final String scene;

    /** 模型调用序号（从 1 开始递增） */
    private final AtomicInteger callSeq = new AtomicInteger(0);

    /** 是否处于 Agent 推理阶段（true=受循环上限约束；降级直连置 false 解除约束） */
    private volatile boolean agentPhase = true;

    /** 最近一轮调用的输入 token 数（终答消息落库留痕用，null=未知） */
    private volatile Integer lastTokenIn;

    /** 最近一轮调用的输出 token 数（终答消息落库留痕用，null=未知） */
    private volatile Integer lastTokenOut;

    /**
     * 私有构造：统一走 begin() 工厂方法
     */
    private AgentCallContext(String requestId, AiPurposeEnum purpose, Long userId, String scene) {
        this.requestId = requestId;
        this.purpose = purpose;
        this.userId = userId;
        this.scene = scene;
    }

    /**
     * 开启一次生成请求的调用上下文（绑定当前线程）
     *
     * @param requestId 请求幂等键
     * @param purpose   用途打标
     * @param userId    发起用户 ID
     * @param scene     业务场景快照
     * @return 新建的上下文实例
     */
    public static AgentCallContext begin(String requestId, AiPurposeEnum purpose, Long userId, String scene) {
        AgentCallContext context = new AgentCallContext(requestId, purpose, userId, scene);
        HOLDER.set(context);
        return context;
    }

    /**
     * 获取当前线程的调用上下文
     *
     * @return 当前上下文；未开启时返回 null（计量装饰模型据此判断是否跳过计量）
     */
    public static AgentCallContext get() {
        return HOLDER.get();
    }

    /**
     * 清理当前线程上下文（必须在 finally 中调用，防线程池复用串号）
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 取下一次模型调用的序号（原子递增）
     *
     * @return 调用序号（从 1 开始）
     */
    public int nextCallSeq() {
        return callSeq.incrementAndGet();
    }

    /**
     * 查询当前已消耗的调用次数（未自增；循环上限判定用）
     *
     * @return 当前已消耗调用次数
     */
    public int currentCallSeq() {
        return callSeq.get();
    }

    /**
     * 是否处于 Agent 推理阶段（循环上限只约束该阶段）
     *
     * @return true=Agent 阶段
     */
    public boolean isAgentPhase() {
        return agentPhase;
    }

    /**
     * 退出 Agent 阶段（降级直连前调用：解除循环上限约束，callSeq 继续累计）
     */
    public void leaveAgentPhase() {
        this.agentPhase = false;
    }

    /**
     * 记录最近一轮调用 token 消耗（终答消息落库留痕用）
     *
     * @param tokenIn  输入 token 数（可 null）
     * @param tokenOut 输出 token 数（可 null）
     */
    public void recordLastTokens(Integer tokenIn, Integer tokenOut) {
        this.lastTokenIn = tokenIn;
        this.lastTokenOut = tokenOut;
    }

    public String getRequestId() {
        return requestId;
    }

    public AiPurposeEnum getPurpose() {
        return purpose;
    }

    public Long getUserId() {
        return userId;
    }

    public String getScene() {
        return scene;
    }

    public Integer getLastTokenIn() {
        return lastTokenIn;
    }

    public Integer getLastTokenOut() {
        return lastTokenOut;
    }
}
