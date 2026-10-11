package com.litisle.commoncore.enums;


/**
 * 整数码字典枚举统一契约（int code 型枚举，如 @EnumValue 入库为 tinyint 的枚举）。
 *
 * 【接入方式】枚举实现此接口（getCode()/getDesc() 通常已有，仅需加 implements），
 * 即自动获得 MVC 参数绑定三值兼容：name（HIGH，忽略大小写）/ code（1）/ desc（高危）
 * —— 由 IntDictEnumConverterFactory 提供转换，业务模块零配置。
 *
 * 【使用场景】code 为 int 型且面向客户端传参的枚举（如 WordLevelEnum/WordListTypeEnum）；
 * 与 DictEnum 的分工：DictEnum 管String code（入库 varchar），本契约管 int code（入库 tinyint），
 * 两者互斥实现，避免 ConverterFactory 目标类型分支冲突。
 *
 * 【为何不用 DictEnum】DictEnum.getCode() 返回 String，int code 枚举强行接入需改字段类型，
 * 会破坏 @EnumValue int 入库契约（参见 DictEnum 注意事项）。
 */
public interface IntDictEnum {

    /** 标识码（如 1，入库 tinyint），兼容绑定值之一 */
    int getCode();

    /** 中文描述（如 高危），兼容绑定值之一；@JsonValue 标注时亦为 JSON 序列化值 */
    String getDesc();

    /**
     * 枚举常量名（如 HIGH），兼容绑定值之一（忽略大小写）。
     * default 方法暴露原因同 DictEnum.getName()：工厂泛型上界不感知 Enum，(Enum<?>) this 转型恒成立。
     */
    default String getName() {
        return ((Enum<?>) this).name();
    }
}
