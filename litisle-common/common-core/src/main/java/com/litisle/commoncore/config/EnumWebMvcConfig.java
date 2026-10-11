package com.litisle.commoncore.config;

import com.litisle.commoncore.converter.IntDictEnumConverterFactory;
import com.litisle.commoncore.converter.StringToDictEnumConverterFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 枚举参数绑定自动配置：注册 DictEnum（String code）与 IntDictEnum（int code）三值兼容转换工厂
 * （全工程一处注册，模块零重复）。
 *
 * 【守卫】@ConditionalOnWebApplication(SERVLET)：
 * WebMvcConfigurer 依赖 Servlet 栈，网关（WebFlux）等非 Servlet 消费方引入 common-core 时
 * 本配置自动跳过，避免 NoClassDefFoundError——与 Gateway 匿名白名单需 WebFlux 独立实现同理。
 *
 * 【优先级】DefaultFormattingConversionService 先注册默认工厂，本工厂后注册；
 * Spring 按"目标类型层级"查找转换器（BizTypeEnum → DictEnum → Enum），
 * DictEnum 分支先于默认 Enum 分支命中，故接入枚举以本工厂为准，未接入枚举走默认，无冲突。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class EnumWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new StringToDictEnumConverterFactory());
        // int code 枚举（如 WordLevelEnum/WordListTypeEnum）三值绑定：name/code/desc 通吃
        registry.addConverterFactory(new IntDictEnumConverterFactory());
    }
}
