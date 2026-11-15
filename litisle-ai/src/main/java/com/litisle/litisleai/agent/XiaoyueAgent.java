package com.litisle.litisleai.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * 小悦 ChatAgent（板块二 2.1：LangChain4j AiServices 装配的生成式 Agent 接口；板块三 3.2 接入会话记忆）
 *
 * <p>【职责边界】本接口只声明对话能力，装配（system prompt / 工具注册 / 计量模型 /
 * 记忆窗口）在 AiAgentConfig；Agent 自主决定调哪个工具、调几次，产出为建议态文本——
 * 不直接写业务库，落库决策在 service 层（D4：作者确认后由 content 落库）。</p>
 *
 * <p>【循环上限】Agent 推理循环上限由 MeteringAgentChatModel 按轮计数强制执行
 * （初值 5 轮），超限抛 AI_AGENT_LOOP_EXCEEDED 由 service 降级为固定管道直连。</p>
 *
 * <p>【会话口径（板块三 3.2）】memoryId = 会话 ID（有会话多轮记忆）或一次性 requestId
 * （无会话单轮，键 TTL 30 分钟自然过期不跨请求）；记忆滑窗由 MessageWindowChatMemory
 * + RedisChatMemoryStore 承担，DB 全量存档由 AiSessionService 承担。</p>
 *
 * @author litisle
 */
public interface XiaoyueAgent {

    /**
     * 发起一次小悦对话（意图指令 + 业务上下文由 service 组装进 userMessage；
     * Agent 自主编排工具：取稿件/查标签池/敏感词预检/知识库检索）
     *
     * @param memoryId    记忆 ID（会话 ID 或一次性 requestId，区分记忆窗口）
     * @param userMessage 用户消息（意图指令组装后的完整文本）
     * @return 模型终答文本（结构化 JSON 或问答回答，由意图决定）
     */
    String chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
