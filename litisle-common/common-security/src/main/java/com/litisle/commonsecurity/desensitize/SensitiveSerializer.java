package com.litisle.commonsecurity.desensitize;



import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

/**
 * 脱敏序列化器（Jackson 自定义序列化器）
 *
 * 【设计说明】
 * 1. 实现 JsonSerializer<String>：自定义 String 字段的序列化逻辑
 * 2. 实现 ContextualSerializer：获取字段上的 @Sensitive 注解，动态选择脱敏策略
 * 3. 每个标注 @Sensitive 的字段在首次序列化时创建独立的 Serializer 实例
 *
 * 【ContextualSerializer 原理】
 * - Jackson 序列化时，会调用 createContextual 方法
 * - 该方法传入 BeanProperty，可获取字段上的注解
 * - 根据注解配置创建新的 Serializer 实例（缓存策略，避免重复解析注解）
 * - 后续该字段的序列化都使用这个实例
 *
 * 【执行流程】
 * 1. Jackson 准备序列化 UserVO.phone 字段
 * 2. 调用 createContextual，读取 @Sensitive(strategy=PHONE)
 * 3. 创建 SensitiveSerializer 实例，保存 strategy=PHONE
 * 4. 调用 serialize，执行 PHONE 策略脱敏
 * 5. 写入脱敏后的值到 JSON
 *
 * 【面试要点】
 * - 为什么要用 ContextualSerializer：因为不同字段可能用不同策略，需要动态感知注解
 * - JsonSerializer vs ContextualSerializer：前者无状态，后者有上下文（注解信息）
 * - 性能：Jackson 会缓存 createContextual 的结果，不会每次序列化都解析注解
 *
 * @author litisle
 */
@Slf4j
public class SensitiveSerializer extends JsonSerializer<String> implements ContextualSerializer {

    /**
     * 当前字段配置的脱敏策略
     */
    private SensitiveStrategy strategy;

    /**
     * 无参构造（Jackson 反射创建时使用）
     */
    public SensitiveSerializer() {
    }

    /**
     * 有参构造（createContextual 中创建带策略的实例）
     */
    public SensitiveSerializer(SensitiveStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * 序列化方法：执行脱敏逻辑
     *
     * @param value       原始值
     * @param gen         JSON 生成器
     * @param serializers 序列化提供者
     */
    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        if (strategy == null) {
            // 未配置策略，原样输出（容错）
            gen.writeString(value);
            return;
        }
        // 执行脱敏
        String desensitized = strategy.desensitize(value);
        gen.writeString(desensitized);
    }

    /**
     * 上下文创建方法：根据字段注解动态配置策略
     *
     * 【说明】
     * - Jackson 首次序列化某字段时调用此方法
     * - 读取字段上的 @Sensitive 注解，获取 strategy
     * - 返回新的 Serializer 实例（带策略），Jackson 会缓存复用
     *✅ 会自动执行，而且是 Jackson 自动执行的，你永远不需要、也不能手动调用它。
     *
     * @param prov      序列化提供者
     * @param property  字段属性（包含注解信息）
     * @return 带策略的 SensitiveSerializer 实例
     */
    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property) {
        // property 为 null（如根对象），返回默认实例
        if (property == null) {
            return this;
        }
        // 读取字段上的 @Sensitive 注解
        Sensitive annotation = property.getAnnotation(Sensitive.class);
        if (annotation == null) {
            // 字段无注解，返回默认实例
            return this;
        }
        // 返回带策略的新实例
        return new SensitiveSerializer(annotation.strategy());
    }
}