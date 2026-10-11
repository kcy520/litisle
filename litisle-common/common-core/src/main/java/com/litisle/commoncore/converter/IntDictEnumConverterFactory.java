package com.litisle.commoncore.converter;


import com.litisle.commoncore.enums.IntDictEnum;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * IntDictEnum 三值兼容转换工厂（name / code(int) / desc 通吃），与 StringToDictEnumConverterFactory 对称。
 *
 * 【背景】int code 枚举（如 WordLevelEnum，@EnumValue 入库 tinyint）无法接入 DictEnum（code 须为 String），
 * 落回 Spring 默认 StringToEnumConverterFactory 只认 name，前端传 desc（如"中危"）报参数类型不匹配。
 *
 * 【注册】由 EnumWebMvcConfig 统一注册为 ConverterFactory<String, IntDictEnum>，
 * Spring 按目标类型层级匹配（WordLevelEnum → IntDictEnum 优先于 → Enum），
 * 与 DictEnum 工厂按各自接口分支命中，互不干扰。
 *
 * 【失败口径】三值均未命中抛 IllegalArgumentException，
 * 由 MVC 包装为 MethodArgumentTypeMismatchException → GlobalExceptionHandler 统一返回参数错误。
 */
public class IntDictEnumConverterFactory implements ConverterFactory<String, IntDictEnum> {

    @Override
    public <T extends IntDictEnum> Converter<String, T> getConverter(Class<T> targetType) {
        return source -> {
            T[] constants = targetType.getEnumConstants();
            if (constants == null) {
                throw new IllegalArgumentException(
                        targetType.getSimpleName() + " 不是枚举，无法完成 IntDictEnum 转换");
            }
            String trimmed = source.trim();
            for (T constant : constants) {
                if (constant.getName().equalsIgnoreCase(trimmed)
                        || String.valueOf(constant.getCode()).equals(trimmed)
                        || constant.getDesc().equals(trimmed)) {
                    return constant;
                }
            }
            throw new IllegalArgumentException(
                    "无效枚举值 '" + source + "'，期望 " + targetType.getSimpleName()
                            + " 的 name/code/desc 之一");
        };
    }
}
