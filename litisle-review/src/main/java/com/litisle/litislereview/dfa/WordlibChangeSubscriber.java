package com.litisle.litislereview.dfa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.dfa.WordlibChangedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 词库变更广播订阅端（review 实例自建 DFA 引擎的消费入口，§2.5）
 *
 * <p>收到 review:wordlib:change 广播后交 {@link WordlibRebuildExecutor} 异步重建
 * （批次2 ④：rebuild 秒级耗时禁止占用订阅线程；执行器内部 version 乐观比对
 * ≤当前版本忽略防乱序 + per-scene 串行去重 + 原子换引擎）；
 * 本实例发起的变更同样经广播触发重建（幂等），统一路径无需本地直调</p>
 *
 * <p>【异常纪律】解析失败仅记 error 不抛出：单条广播丢失由下次变更或重启收敛，
 * 抛异常只会打断监听循环且无法恢复（Pub 无重投）；
 * 构建期失败由执行器兜底（旧引擎保留 + 失败指标），本类不感知</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WordlibChangeSubscriber implements MessageListener {

    /** 热更构建执行器（独立单线程池，订阅线程仅投递任务即刻返回） */
    private final WordlibRebuildExecutor rebuildExecutor;
    private final ObjectMapper objectMapper;

    /**
     * 广播回调：JSON → WordlibChangedMessage → 投递异步重建任务（version 乐观比对在构建时兜底）
     *
     * 被框架回调执行的 ，调用链路在 DfaConfig.java:42-53 里完成注册：
     * 被subscriber 注册到 channel 上
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
//
//      container.addMessageListener(subscriber, new ChannelTopic(ModerationConstant.WORDLIB_CHANGE_CHANNEL));
//         运营改词库 → WordlibService 同事务递增版本 + 发布广播
//         │
//         ▼  Redis PUBLISH review:wordlib:change（channel 常量在 ModerationConstant）
//    Redis 服务端把消息推给所有订阅该 channel 的连接
//              container.addMessageListener(subscriber, new ChannelTopic (ModerationConstant.WORDLIB_CHANGE_CHANNEL));
//         │
//         ▼  RedisMessageListenerContainer（Spring 后台线程，随应用启动常驻）
//    容器按 channel 匹配 → 找到注册的 WordlibChangeSubscriber
//         │
//         ▼  框架反射回调
//    onMessage(message, pattern)  ← 你选中的这段代码在这里被执行
        try {
            // Step1 广播体是 JSON 字节 → String → 反序列化为契约消息
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            WordlibChangedMessage msg = objectMapper.readValue(body, WordlibChangedMessage.class);
            log.info("收到词库变更广播：scene={}, version={}, operatorId={}",
                    msg.scene(), msg.version(), msg.operatorId());
            // Step2 投递异步热更任务（执行器串行+去重；rebuild 内 version 乐观比对：乱序/重复广播被忽略，幂等）
            rebuildExecutor.submit(msg.scene());
        } catch (Exception e) {
            // 任何失败仅记日志不抛出：Pub 无重投，抛异常只会打断监听线程且无法恢复
            log.error("词库变更广播处理失败（该实例沿用旧词库直至下次广播）", e);
        }
    }
}