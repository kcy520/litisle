package com.litisle.commonsecurity.desensitize;



import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据脱敏注解
 *
 * 【设计说明】
 * 1. 标注在 VO 字段上，响应 JSON 时自动脱敏
 * 2. 基于 Jackson 自定义序列化器实现，业务代码无感知
 * 3. 搭配 @JacksonAnnotationsInside 元注解，将 @JsonSerialize 行为内聚
 *
 * 【实现原理】
 * 1. Jackson 序列化时，遇到标注 @Sensitive 的字段
 * 2. 调用 SensitiveSerializer 的 serialize 方法
 * 3. 根据注解中的 strategy 执行对应脱敏逻辑
 * 4. 输出脱敏后的值到 JSON
 *
 * 【优势】
 * - 业务无感知：只需加注解，无需手动调用脱敏方法
 * - 灵活控制：可针对不同字段使用不同策略
 * - 不影响存储：数据库存原始值，仅响应时脱敏
 *
 * 【使用示例】
 * public class UserVO {
 *     private Long userId;
 *
       @Sensitive(strategy = SensitiveStrategy.PHONE)
 *     private String phone;        // 138****8888
 *
       @Sensitive(strategy = SensitiveStrategy.ID_CARD)
 *     private String idCard;       // 110***********1234
 *
       @Sensitive(strategy = SensitiveStrategy.EMAIL)
 *     private String email;        // ex***@xx.com
 *
       @Sensitive(strategy = SensitiveStrategy.NAME)
 *     private String realName;     // 张**
 * }
 *
 * @author litisle
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = SensitiveSerializer.class)
public @interface Sensitive {

    /**
     * 脱敏策略（必填）
     */
    SensitiveStrategy strategy();
}