package com.litisle.commonredis.config;



import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * RedisTemplate 配置类
 * 
 * 【设计说明】
 * 1. 配置 RedisTemplate 的序列化方式，解决乱码问题
 * 2. 默认的 JdkSerializationRedisSerializer 序列化后存的是二进制数据
 *    在 RedisInsight 中看到的是乱码，不利于调试
 * 3. 改为 StringRedisSerializer + Jackson2JsonRedisSerializer
 *    - Key 用 StringRedisSerializer：可读性强
 *    - Value 用 Jackson2JsonRedisSerializer：序列化为 JSON
 * 
 * 【序列化方案对比】
 * ├───────────────────────┼───────────────────────┤
 * │ 方案                  │ 说明                   │
 * ├───────────────────────┼───────────────────────┤
 * │ StringRedisSerializer │ 直接存字符串，可读性最强 │
 * │ Jackson2JsonSerializer│ JSON 格式，可读性较好   │
 * │ JdkSerializationRedis │ 二进制，不可读（默认）   │
 * └───────────────────────┴───────────────────────┘
 * 
 * @author litisle
 */
@Configuration
@ConditionalOnClass(RedisTemplate.class)
public class RedisConfig {

    /**
     * 日期时间格式（与 common-core 的 JacksonConfig 保持一致）
     */
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /**
     * 自定义 RedisTemplate
     * 
     * 【学习要点】
     * 1. keySerializer：Key 的序列化方式
     * 2. valueSerializer：Value 的序列化方式
     * 3. hashKeySerializer：Hash 结构中 field 的序列化方式
     * 4. hashValueSerializer：Hash 结构中 value 的序列化方式
     * 
     * @param factory Redis 连接工厂
     * @return RedisTemplate 实例
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // ====== 1. 配置 Jackson 序列化器（用于 Value） ======
        Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper mapper = new ObjectMapper();

        // 设置所有字段的可见性（private、public 都可见）
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // 启用默认类型信息（存JSON时带上类名，反序列化时才知道转成什么类型）
        // 【说明】反序列化时，根据 JSON 中的 @class 字段知道要转成什么类
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        // 注册 Java 8 日期时间模块
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(LocalDateTime.class,
                new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));
        javaTimeModule.addDeserializer(LocalDateTime.class,
                new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN)));
        mapper.registerModule(javaTimeModule);

        jsonSerializer.setObjectMapper(mapper);

        // ====== 2. 配置 String 序列化器（用于 Key） ======
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // ====== 3. 设置序列化器 ======
        // Key 使用 String 序列化（方便查看和调试）
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 使用 JSON 序列化（可读性好）
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        // 初始化 Bean
        template.afterPropertiesSet();

        return template;
    }

    /**
     * StringRedisTemplate（用于纯字符串操作）
     * 
     * 【说明】
     * 1. StringRedisTemplate 是 RedisTemplate<String, String> 的特化
     * 2. Key 和 Value 都使用 StringRedisSerializer
     * 3. 适合存储计数、验证码等纯字符串场景
     * 
     * @param factory Redis 连接工厂
     * @return StringRedisTemplate 实例
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        return template;
    }
}