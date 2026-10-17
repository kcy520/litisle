package com.litisle.litisleuser.dfa;

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
 * DFA 同步闸门装配（user 宿主侧，与 auth 宿主 AuthDfaConfig 同构，板块四 4-7 用户名口径复用）
 *
 * <p>① DfaFacade Bean：挂接 Redis 快照装载器 RedisWordLibraryLoader（每场景独立 houbb 引擎）；
 * ② SmartInitializingSingleton：全部单例就绪后 initAll() 逐文本场景构建引擎——
 * 任何场景装载失败（含兜底快照不可用）抛异常中断启动（宁严勿松，杜绝带病上线）；
 * ③ 词库广播监听容器：订阅 review:wordlib:change 触发热更（rebuild 内 version 乐观比对防乱序）</p>
 *
 * <p>【接入场景】改名链路（PUT /api/user/profile 携带 username）同步 DFA USERNAME 判定，
 * HIGH 拒绝 + 取证落库（口径与 auth 注册链路一致，决策 SSOT 收口 DfaPublishGate.decide）</p>
 *
 * @author litisle
 */
@Slf4j
@Configuration
public class UserDfaConfig {

    /**
     * DFA 门面 Bean：词库数据源唯一入口为 WordLibraryLoader（user 侧 Redis 快照实现）
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