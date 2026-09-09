package com.litisle.commoncore.config;



import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局配置
 * 
 * 【设计说明】
 * 1. 统一配置 JSON 序列化/反序列化规则
 * 2. 解决 LocalDateTime 序列化问题（默认不支持）
 * 3. 使用 @ConditionalOnClass 确保仅在引入 Jackson 时生效
 * 
 * 【配置内容】
 * - 日期格式：yyyy-MM-dd HH:mm:ss
 * - 空值处理：不序列化 null 字段（可选）
 * - 缩进：生产环境关闭，开发环境可开启便于调试
 * 
 * 【学习要点】
 * - Jackson2ObjectMapperBuilderCustomizer：Spring Boot 提供的定制器
 * - JavaTimeModule：支持 Java 8 日期时间类型
 * 
 * @author litisle
 */
@Configuration
@ConditionalOnClass(ObjectMapper.class)
public class JacksonConfig {

    /**
     * 日期时间格式
     */
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 自定义 Jackson 配置
     * 
     * @return Jackson2ObjectMapperBuilderCustomizer 定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jackson2ObjectMapperBuilderCustomizer() {
        return builder -> {
                // ✅ 1️⃣ 把 LocalDateTime 从「默认格式」改成「自定义字符串格式」
                // ✅ 2️⃣ 禁止把日期序列化成「时间戳」（ 时间戳（毫秒），  "createTime": 1725868800000)
                // ✅ 3️⃣ 全局生效，业务代码零侵入
            // 注册 Java 8 日期时间模块
            JavaTimeModule javaTimeModule = new JavaTimeModule();
            
            // LocalDateTime 序列化器
            javaTimeModule.addSerializer(LocalDateTime.class, 
                    new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));
            
            // LocalDateTime 反序列化器
            javaTimeModule.addDeserializer(LocalDateTime.class, 
                    new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));
            
            builder.modules(javaTimeModule);
            
            // 禁用将日期序列化为时间戳（使用字符串格式）
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            // 枚举反序列化容错：空字符串 "" 一律按 null 处理（企业规范：库层全局兑底，业务零侵入）
            // 解决前端传 "source": "" 时抛出 Cannot coerce empty String to enum
            // 作用于所有枚举类型（Enum.class），jackson-databind 2.12+ 支持，当前 2.21.4
            builder.postConfigurer(objectMapper ->
                    objectMapper.coercionConfigFor(Enum.class)
                            .setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull));
        };
    }
}