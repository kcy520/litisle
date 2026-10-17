package com.litisle.litislecontent.dfa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.WordlibChangedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 词库变更广播订阅端（content 宿主 DFA 引擎热更消费入口，§2.5）
 *
 * <p>收到 review:wordlib:change 广播后调 {@link DfaFacade#rebuild}：
 * 内部 version 乐观比对（≤当前版本忽略，防 Pub 乱序旧覆盖新）+ 原子换引擎；
 * rebuild 时经 {@link ContentRedisWordLibraryLoader} 读取 Redis 快照装载新词库</p>
 *
 * <p>【异常纪律】解析失败/重建失败仅记 error 不抛出：单条广播丢失由下次变更或重启收敛，
 * 抛异常只会打断监听循环且无法恢复（Pub 无重投）</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WordlibChangeSubscriber implements MessageListener {

    /** DFA 门面（rebuild 入口） */
    private final DfaFacade dfaFacade;

    /** 广播 JSON 反序列化器 */
    private final ObjectMapper objectMapper;

    /**
     * 广播回调：JSON → WordlibChangedMessage → rebuild（version 乐观比对兜底）
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // Step1 广播体是 JSON 字节 → String → 反序列化为契约消息
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            WordlibChangedMessage msg = objectMapper.readValue(body, WordlibChangedMessage.class);
            log.info("收到词库变更广播：scene={}, version={}, operatorId={}",
                    msg.scene(), msg.version(), msg.operatorId());
            // Step2 触发本实例重建（rebuild 内 version 乐观比对：乱序/重复广播被忽略，幂等）
            dfaFacade.rebuild(msg.scene());
        } catch (Exception e) {
            // 任何失败仅记日志不抛出：Pub 无重投，抛异常只会打断监听线程且无法恢复
            log.error("词库变更广播处理失败（该实例沿用旧词库直至下次广播）", e);
        }
    }
}
