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
 * <p>【通俗说明】发往模型的"安检门"：程序和模型之间的每一段对话（不管是提问、
 * 系统指令还是工具带回来的稿件片段），出站前都要从这道门里过一遍，把敏感信息
 * 换成占位符后才真正发出去。它自己不做脱敏（那是 OutboundSanitizer 的活），
 * 只负责"拦下来、逐条处理、再放行"的接线工作；配置里把脱敏开关关掉，
 * 这道门就变成透明玻璃，内容原样通过。</p>
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
     *
     * <p>【通俗说明】装配装饰链：把底层通道、脱敏过滤器、开关配置三样东西拿在手里，
     * 等调用进来时按开关决定"脱敏放行"还是"原样透传"。</p>
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
     *
     * <p>【通俗说明】两步：开关关着就直接把请求原样转给底层通道；开着就先对请求里
     * 的每一条消息做脱敏（具体怎么洗交给 sanitizeMessage），再用洗完的消息重新
     * 组装请求发出去。模型的响应不做任何处理，原样往上传。</p>
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
     *
     * <p>【通俗说明】按消息类型分别处理，四种消息都照顾到：系统消息、用户消息取
     * 文本脱敏；AI 消息比较特殊——它可能同时带文字和"工具调用指令"，只洗文字部分，
     * 工具调用指令原样保留（洗坏了模型就不知道该调什么工具了）；工具执行结果回喂
     * 的文本里可能带稿件片段，同样要洗。其他没文字的消息直接放行。</p>
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
