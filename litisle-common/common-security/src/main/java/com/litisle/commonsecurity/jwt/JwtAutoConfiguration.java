package com.litisle.commonsecurity.jwt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 自动配置类
 *
 * 【设计说明】
 * 1. 在 litisle.security.jwt.secret 已配置时，自动创建 JwtTokenProvider Bean
 * 2. 支持 @ConditionalOnMissingBean，允许业务服务覆盖默认实现
 * 3. 启动时校验密钥长度，符合 Fail-Fast 原则
 * 4. 将 yml 中的秒级配置转换为毫秒级，供 JwtTokenProvider 使用
 *
 * 【生效条件】
 * - 必须配置 litisle.security.jwt.secret（密钥）
 * - 未配置时 JwtTokenProvider Bean 不创建，网关和业务服务启动时不会报错
 * - 适用于：不需要 JWT 的服务（如部分内部服务）不配置密钥即可
 *
 * @author litisle
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@ConditionalOnProperty(prefix = "litisle.security.jwt", name = "secret")
public class JwtAutoConfiguration {

    /**
     * 创建 JwtTokenProvider Bean
     *
     * 启动时校验密钥长度，Fail-Fast
     * 将秒级配置转换为毫秒级
     *
     * @param properties JWT 配置属性
     * @return JwtTokenProvider 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public JwtTokenProvider jwtTokenProvider(JwtProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT 密钥未配置（litisle.security.jwt.secret）");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足 256 bit，至少需要 32 个字符，当前为 " + secret.length() + " 字符");
        }

        // 秒转毫秒
        long accessTokenExpireMs = properties.getAccessTokenExpire() * 1000;
        long rememberMeExpireMs = properties.getRememberMeExpire() * 1000;

        log.info("JWT 令牌提供者初始化完成，Access Token 有效期={}秒，记住我有效期={}秒",
                properties.getAccessTokenExpire(), properties.getRememberMeExpire());

        return new JwtTokenProvider(secret, accessTokenExpireMs, rememberMeExpireMs);
    }
}