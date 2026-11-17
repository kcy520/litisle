package com.litisle.litisleai.provider.text;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiProviderProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容文本模型通道实现（P2 定档：DeepSeek API 国内直连，LangChain4j OpenAI 模块承载 SDK/HTTP）
 *
 * <p>【职责边界】本类是唯一接触模型 SDK 的地方：组装 messages → 调 SDK → 提取输出与 token 用量；
 * prompt 组织、计量、预算均在 service 层。换供应商只改 yml（base-url/api-key/model 外置）。</p>
 *
 * <p>【失败语义】fail-fast：SDK 异常原样上抛（SDK 内置瞬时重试 maxRetries 兜底），
 * 不静默吞错、不默认放行；通道未启用（ai.provider.text.enabled=false）时同样抛明确异常。</p>
 *
 * <p>【日志纪律】只记字符数/模型标识/token 数/耗时与合规凭证 ID，禁止打印 prompt 正文全文。</p>
 *
 * <p>【观测】ai.guard.state{provider} Gauge 由装饰链中的熔断自检装饰器
 * （CircuitBreakerChatModel）持有与注册，本类不再单独维护健康标记。</p>
 *
 * <p>【通俗说明】文本模型通道的"真身"：前面那些接口和装饰器都是壳，真正拨号打给
 * DeepSeek 的是这里。它做的事很单纯——把两段 prompt 装进 SDK 要求的消息格式、
 * 发出去、等回复，然后从回复里抠出正文和 token 用量打包成回执交上去。它对失败
 * 的态度很硬：模型没响应就报错上抛（SDK 自带几次瞬时重试兜底），绝不编个假回复
 * 蒙混过关；AI 功能没开时也是明确报"通道未启用"，而不是悄悄装没事发生。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
public class OpenAiCompatTextModelProvider implements TextModelProvider {

    /** 模型通道（enabled=false 时容器无此 Bean，ObjectProvider 取空 → 调用时抛明确异常） */
    private final ObjectProvider<ChatModel> chatModelProvider;

    /** 通道配置（providerName 指标标签 / model 版本快照来源） */
    private final AiProviderProperties properties;

    /**
     * 构造注入：模型通道按需取用（未启用时 Bean 缺席不阻断启动）
     *
     * <p>【通俗说明】用 ObjectProvider 拿模型通道，而不是直接塞一个：好处是 AI 功能
     * 没启用时这个 Bean 根本不存在，程序也能正常启动，等真调用了再报"未启用"。</p>
     */
    public OpenAiCompatTextModelProvider(ObjectProvider<ChatModel> chatModelProvider,
                                         AiProviderProperties properties) {
        this.chatModelProvider = chatModelProvider;
        this.properties = properties;
    }

    /**
     * 发起一次文本模型调用：成功返回输出与 token 用量；失败 fail-fast 抛 BizException（含原始原因）
     *
     * <p>【通俗说明】完整流程：① 先确认通道已启用（没启用直接报错）；② 把 system +
     * user 两段话按 SDK 格式组装，调用方指定了温度就一并带上（机审传 0 让输出稳定）；
     * ③ 发起调用，SDK 内部自带瞬时重试；④ 从回复里提取正文、token 进出量、供应商
     * 流水号，连同耗时一起打进日志（只记数字不记正文）；⑤ 打包成回执返回。中途任何
     * 异常都包装成明确错误上抛，绝不吞掉。</p>
     */
    @Override
    public TextChatResult chat(TextChatRequest request) {
        // 通道未启用（无 api-key / enabled=false）：Bean 缺席，抛明确错误码而非静默失败
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "AI 模型通道未启用（litisle.ai.provider.text.enabled=false）");
        }
        long start = System.currentTimeMillis();
        try {
            // 组装 system+user 双段消息；显式温度覆盖仅在有值时下发，否则走模型默认配置
            ChatRequest.Builder requestBuilder = ChatRequest.builder()
                    .messages(SystemMessage.from(request.getSystemPrompt()), UserMessage.from(request.getUserPrompt()));
            if (request.getTemperature() != null) {
                // 每请求温度覆盖（机审传 0 保证确定性输出）
                requestBuilder.parameters(ChatRequestParameters.builder()
                        .temperature(request.getTemperature())
                        .build());
            }
            // 发起调用：SDK 内部按 maxRetries 做瞬时重试，耗尽抛异常走 fail-fast
            ChatResponse response = chatModel.chat(requestBuilder.build());
            // 提取输出与用量：token 数可能为 null（供应商未返回），计量侧按 0 兜底
            Integer tokenIn = response.tokenUsage() == null ? null : response.tokenUsage().inputTokenCount();
            Integer tokenOut = response.tokenUsage() == null ? null : response.tokenUsage().outputTokenCount();
            log.info("模型通道调用成功 provider={} model={} promptChars={} tokenIn={} tokenOut={} costMs={} traceId={}",
                    properties.getProviderName(), properties.getModel(),
                    request.getUserPrompt() == null ? 0 : request.getUserPrompt().length(),
                    tokenIn, tokenOut, System.currentTimeMillis() - start, response.metadata().id());
            return TextChatResult.builder()
                    .content(response.aiMessage().text())
                    .modelVersion(properties.getModel())
                    .providerTraceId(response.metadata().id())
                    .tokenIn(tokenIn)
                    .tokenOut(tokenOut)
                    .build();
        } catch (BizException e) {
            // 本类自身的明确异常直接透出（通道未启用）
            throw e;
        } catch (Exception e) {
            // SDK/网络异常 fail-fast：异常包装后上抛（不吞错）；健康观测由装饰链熔断自检承担
            log.error("模型通道调用失败 provider={} model={} costMs={}",
                    properties.getProviderName(), properties.getModel(), System.currentTimeMillis() - start, e);
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "AI 模型调用失败", e);
        }
    }
}
