package com.litisle.litisleai.config;

import com.litisle.litisleai.agent.MeteringAgentChatModel;
import com.litisle.litisleai.agent.XiaoyueAgent;
import com.litisle.litisleai.agent.tools.XiaoyueTools;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.memory.RedisChatMemoryStore;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.service.AiUsageRecordService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * 通俗说明：
 * 这个类负责把"小悦"这个 AI 助手拼装出来：接上大模型、设定人设（系统提示词）、
 * 挂上它能调用的工具、接上会话记忆（Redis 存最近几条对话），
 * 最后套一层计量装饰——统计每次调用花了多少、并限制最多来回几轮防死循环。
 * 模型通道 enabled=false 时不创建这个助手，接口返回"服务未开启"的明确错误码，不会崩。
 */
/**
 * 小悦 Agent 装配（板块二 2.1：LangChain4j AiServices 唯一装配点）
 *
 * <p>【装配链】XiaoyueAgent（AiServices 代理）→ MeteringAgentChatModel（计量+轮次上限）
 * → DesensitizingChatModel（出站脱敏）→ OpenAiChatModel（SDK）。工具集注册与系统提示词
 * 在本类收口；ChatMemory 随板块三 3.2 接入（届时补 chatMemoryProvider）。</p>
 *
 * <p>【条件装配】模型通道 enabled=false 时不装配 Agent Bean——Agent 端点抛 80005
 * （与机审通道同口径），本地无 api-key 也可启动服务。</p>
 *
 * <p>【双重循环护栏】maxSequentialToolsInvocations 限制连续工具调用次数（框架层）；
 * MeteringAgentChatModel 按轮计数强制 5 轮上限（业务层，超限降级直连），两者互为兜底。</p>
 *
 * @author litisle
 */
@Configuration
public class AiAgentConfig {

    /** 计量明细服务（MeteringAgentChatModel 依赖） */
    private final AiUsageRecordService usageRecordService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** Agent 配置（循环上限） */
    private final AiAgentProperties agentProperties;

    /** 通道配置（provider 标签 / model 版本快照） */
    private final AiProviderProperties providerProperties;

    /** 能力工具集（Function Calling 注册对象） */
    private final XiaoyueTools xiaoyueTools;

    /** Redis 会话记忆存储（板块三 3.2：ChatMemoryStore Redis 版） */
    private final RedisChatMemoryStore redisChatMemoryStore;

    /**
     * 构造注入装配依赖
     */
    public AiAgentConfig(AiUsageRecordService usageRecordService,
                         AiMetrics metrics,
                         AiAgentProperties agentProperties,
                         AiProviderProperties providerProperties,
                         XiaoyueTools xiaoyueTools,
                         RedisChatMemoryStore redisChatMemoryStore) {
        this.usageRecordService = usageRecordService;
        this.metrics = metrics;
        this.agentProperties = agentProperties;
        this.providerProperties = providerProperties;
        this.xiaoyueTools = xiaoyueTools;
        this.redisChatMemoryStore = redisChatMemoryStore;
    }

    /**
     * 装配小悦 ChatAgent（AiServices 动态代理）：system prompt + 工具注册 + 计量装饰模型
     *
     * @param chatModelProvider 脱敏装饰模型提供器（enabled=true 时 Bean 必在，直接取用）
     * @return 小悦 Agent 代理实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "litisle.ai.provider.text", name = "enabled", havingValue = "true")
    public XiaoyueAgent xiaoyueAgent(ObjectProvider<ChatModel> chatModelProvider) {
        // 计量装饰模型：轮次上限 + 每轮计量明细（Agent 多轮按轮递增）
        MeteringAgentChatModel meteringModel = new MeteringAgentChatModel(
                chatModelProvider.getObject(), usageRecordService, metrics, providerProperties, agentProperties);
        return AiServices.builder(XiaoyueAgent.class)
                .chatModel(meteringModel)
                // 系统提示词渲染（板块十三 5.3）：基础提示词 + 边界响应规则段（拒绝话术模板 yml 外置注入）
                .systemMessageProvider(memoryId -> AiPromptConstants.renderXiaoyueSystemPrompt(
                        agentProperties.getBoundaryRefusalTemplate()))
                .tools(xiaoyueTools)
                // 会话记忆（板块三 3.2）：Redis 窗口暂存最近 N 条（LRU 暂存 10/30 口径外置）；
                // Memory 不可用时 RedisChatMemoryStore 内部降级为无记忆单轮并告警
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(agentProperties.getMemoryWindowSize())
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .maxSequentialToolsInvocations(agentProperties.getMaxLoopRounds())
                .build();
    }
}
