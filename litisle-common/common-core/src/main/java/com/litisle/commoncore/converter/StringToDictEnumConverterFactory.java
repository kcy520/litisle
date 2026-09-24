package com.litisle.commoncore.converter;

import com.litisle.commoncore.enums.DictEnum;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * DictEnum 三值兼容转换工厂（name / code / desc 通吃）。
 *
 * 【背景】枚举标注 @JsonValue(desc) 后，springdoc 文档下拉框展示 desc（如"头像"），
 * 但 Spring 默认 StringToEnumConverterFactory 只认 name（如 AVATAR），文档选项全部无法通过绑定。
 *
 * 【注册】由 EnumWebMvcConfig 统一注册为 ConverterFactory<String, DictEnum>，
 * Spring 按目标类型层级匹配（BizTypeEnum → DictEnum 优先于 → Enum），
 * 因此仅"实现了 DictEnum 的枚举"启用本工厂，未接入枚举保持默认 name-only 绑定，互不干扰。
 *
 * 【失败口径】三值均未命中抛 IllegalArgumentException，
 * 由 MVC 包装为 MethodArgumentTypeMismatchException → GlobalExceptionHandler 统一返回参数错误。
 */
public class StringToDictEnumConverterFactory implements ConverterFactory<String, DictEnum> {

    @Override
    public <T extends DictEnum> Converter<String, T> getConverter(Class<T> targetType) {
        return source -> {
            T[] constants = targetType.getEnumConstants();
            if (constants == null) {
                throw new IllegalArgumentException(
                        targetType.getSimpleName() + " 不是枚举，无法完成 DictEnum 转换");
            }
            for (T constant : constants) {
                if (constant.getName().equalsIgnoreCase(source)
                        || constant.getCode().equalsIgnoreCase(source)
                        || constant.getDesc().equals(source)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException(
                    "无效枚举值 '" + source + "'，期望 " + targetType.getSimpleName()
                            + " 的 name/code/desc 之一");
        };
    }
}
