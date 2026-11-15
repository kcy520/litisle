package com.litisle.litisleai.provider.text;

import com.litisle.litisleai.config.AiAgentProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;

/**
 * 出站脱敏装饰模型（P1 定档：脱敏在 provider 出站前统一执行的接线点）
 *
 * <p>【职责边界】本类是 ChatModel 装饰器：拦截出站消息列表，逐条文本脱敏后委托底层
 * OpenAI 兼容通道；不接触 prompt 组织与业务语义。装配后它是容器内唯一的 ChatModel Bean，
 * 机审通道（TextModelProvider）与 Agent 通道（AiServices）出站均经过本装饰器，
 * 覆盖 system/user/ai/工具结果四类消息的文本。</p>
 *
 * <p>【开关】litisle.ai.agent.desensitize-enabled=false 时透传不处理（外发开关一键关闭口径）；
 * 脱敏开关默认 true。</p>
 *
 * <p>【失败语义】脱敏为纯内存正则替换，无外部依赖，不产生额外失败路径。</p>
 *
 * @author litisle
 */
public class DesensitizingChatModel implements ChatModel {

    /** 底层 OpenAI 兼容通道（唯一接触模型 SDK 的出口） */
    private final ChatModel delegate;

    /** 脱敏过滤器（四类正则替换） */
    private final OutboundSanitizer sanitizer;

    /** Agent 侧配置（脱敏开关来源） */
    private final AiAgentProperties agentProperties;

    /**
     * 构造注入装饰链（delegate → 本装饰器 → 调用方）
     *
     * @param delegate        底层模型通道
     * @param sanitizer       脱敏过滤器
     * @param agentProperties Agent 侧配置（脱敏开关）
     */
    public DesensitizingChatModel(ChatModel delegate, OutboundSanitizer sanitizer, AiAgentProperties agentProperties) {
        this.delegate = delegate;
        this.sanitizer = sanitizer;
        this.agentProperties = agentProperties;
    }

    /**
     * 出站前对消息逐条脱敏后委托底层通道；开关关闭时整体透传
     *
     * @param request 模型调用请求（消息列表 + 参数）
     * @return 模型响应（原样透传，不做输出侧处理）
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        // 外发开关关闭：整体透传（不脱敏），一键关闭口径
        if (!agentProperties.isDesensitizeEnabled()) {
            return delegate.chat(request);
        }
        // 逐条消息文本脱敏（system/user/ai/工具结果四类；非文本类消息原样透传）
        List<ChatMessage> sanitized = request.messages().stream().map(this::sanitizeMessage).toList();
        return delegate.chat(ChatRequest.builder()
                .messages(sanitized)
                .parameters(request.parameters())
                .build());
    }

    /**
     * 按消息类型提取文本脱敏后重建消息对象（保留工具调用请求等非文本字段）
     *
     * @param message 原始消息
     * @return 脱敏后的同类型消息（无文本或无命中时可能为原对象）
     */
    private ChatMessage sanitizeMessage(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            return SystemMessage.from(sanitizer.sanitize(systemMessage.text()));
        }
        if (message instanceof UserMessage userMessage) {
            return UserMessage.from(sanitizer.sanitize(userMessage.singleText()));
        }
        if (message instanceof AiMessage aiMessage && aiMessage.text() != null) {
            // AI 消息可能同时携带文本与工具调用请求：文本脱敏、调用请求原样保留
            return AiMessage.from(sanitizer.sanitize(aiMessage.text()), aiMessage.toolExecutionRequests());
        }
        if (message instanceof ToolExecutionResultMessage toolResult) {
            // 工具结果回喂文本同样属于出站内容（含稿件正文片段），统一脱敏
            return ToolExecutionResultMessage.from(
                    toolResult.id(), toolResult.toolName(), sanitizer.sanitize(toolResult.text()));
        }
        // 其余消息类型（无单文本语义）原样透传
        return message;
    }
}
