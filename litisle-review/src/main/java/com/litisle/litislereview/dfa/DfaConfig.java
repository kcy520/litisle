package com.litisle.litislereview.dfa;

import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litisleapi.dfa.WordLibraryLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * DFA 同步闸门装配（review 宿主侧，板块二）
 * <p>① DfaFacade Bean：挂接查表装载器 TableWordLibraryLoader（每场景独立 houbb 引擎）；
 * ② SmartInitializingSingleton：全部单例就绪后 initAll() 逐文本场景构建引擎——
 * 任何场景装载失败（含兜底快照不可用）抛异常中断启动（宁严勿松，杜绝带病上线）</p>
 */
@Slf4j
@Configuration
public class DfaConfig {

    @Bean
    public DfaFacade dfaFacade(WordLibraryLoader wordLibraryLoader) {
        return new DfaFacade(wordLibraryLoader);
    }

    /**
     * 启动初始化钩子：全单例就绪后逐场景构建 DFA 引擎（fail-fast，装载失败中断启动）
     */
    @Bean
    public SmartInitializingSingleton dfaInitializer(DfaFacade dfaFacade) {
        return () -> {
            dfaFacade.initAll();
            log.info("DFA 同步闸门初始化完成，文本场景：{}", DfaScenes.textScenes());
        };
    }

    /**
     * 词库广播监听容器：订阅 review:wordlib:change，词库变更广播触发本实例重建
     * DFA（rebuild 内 version 乐观比对防乱序，§2.5）
     */
    @Bean
    public RedisMessageListenerContainer wordlibRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, WordlibChangeSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(ModerationConstant.WORDLIB_CHANGE_CHANNEL));
        return container;
    }
}