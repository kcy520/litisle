package com.litisle.litisleauth.dfa;

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
 * DFA 同步闸门装配（auth 宿主侧，板块四 4-7 USERNAME 注册同步拒绝）
 *
 * <p>① DfaFacade Bean：挂接 Redis 快照装载器 RedisWordLibraryLoader（每场景独立 DFA 引擎，hankcs ACDAT 内核）；
 * ② SmartInitializingSingleton：全部单例就绪后 initAll() 逐文本场景构建引擎——
 * 任何场景装载失败（含兜底快照不可用）抛异常中断启动（宁严勿松，杜绝带病上线）；
 * ③ 词库广播监听容器：订阅 review:wordlib:change 触发热更（rebuild 内 version 乐观比对防乱序）</p>
 *
 * @author litisle
 */
@Slf4j
@Configuration
public class AuthDfaConfig {

    /**
     * DFA 门面 Bean：词库数据源唯一入口为 WordLibraryLoader（auth 侧 Redis 快照实现）
     */
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
     * 词库广播监听容器：订阅 review:wordlib:change，词库变更广播触发本实例引擎热更重建
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