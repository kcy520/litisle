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
 * <p>① DfaFacade Bean：挂接查表装载器 TableWordLibraryLoader（每场景独立 DFA 引擎，hankcs ACDAT 内核）；
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
     *
     * @param connectionFactory
     * @param subscriber
     * @return
     */
    @Bean
    public RedisMessageListenerContainer wordlibRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, WordlibChangeSubscriber subscriber) {

        // 创建监听容器：Spring Data Redis 提供的 Pub/Sub 订阅容器，持有一条后台线程常驻消费订阅消息
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        // 注入 Redis 连接工厂：容器基于该连接向 Redis 服务端发送 SUBSCRIBE 订阅命令并接收推送
        container.setConnectionFactory(connectionFactory);
        // 注册订阅关系：本实例的 WordlibChangeSubscriber 回调 onMessage 绑定到
        // review:wordlib:change 频道——任何实例 PUBLISH 该频道，容器即回调本 subscriber
        container.addMessageListener(subscriber, new ChannelTopic(ModerationConstant.WORDLIB_CHANGE_CHANNEL));
            //container.addMessageListener( subscriber , new ChannelTopic(WORDLIB_CHANGE_CHANNEL) )
            //                           └──甲：回调对象──┘        └──乙：频道（信道标识）──┘
        return container;
    }
}
