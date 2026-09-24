package com.litisle.commoncore.enums;

/**
 * 字典枚举统一契约（仅限 String code 型枚举）。
 *
 * 【接入方式】枚举实现此接口（getCode()/getDesc() 通常已有，仅需加 implements），
 * 即自动获得 MVC 参数绑定三值兼容：name（AVATAR，忽略大小写）/ code（avatar）/ desc（头像）
 * —— 由 StringToDictEnumConverterFactory 提供转换，业务模块零配置。
 *
 * 【使用场景】客户端以 @RequestParam/@PathVariable 传入的枚举（如 BizTypeEnum）；
 * 服务端内部状态枚举（如 UploadStatusEnum/SafeFlagEnum/AccessLevelEnum）不面向客户端传参，
 * 且 code 为 int 型，无需也不应接入本契约。
 *
 * 【注意】code 必须为 String：int code 枚举接入需改字段类型，会破坏 @EnumValue 入库契约，
 * 若将来确有 int 型枚举绑定诉求，另建 IntDictEnum 契约与对应工厂，勿强塞本接口。
 */
public interface DictEnum {

    /** 标识码（如 avatar），兼容绑定值之一 */
    String getCode();

    /** 中文描述（如 头像），兼容绑定值之一；@JsonValue 标注时亦为 JSON 序列化值 */
    String getDesc();

    /**
     * 枚举常量名（如 AVATAR），兼容绑定值之一（忽略大小写）。
     *
     * 【为何用 default 方法而非工厂内直接调 name()】
     * 工厂泛型上界仅为 DictEnum，编译器不感知 T 是 Enum，直接调 name() 编译报错；
     * 而工厂方法签名受 ConverterFactory 接口擦除约束，无法改成 <T extends Enum<T> & DictEnum>。
     * 故在契约内以 default 方法暴露：实现类必为枚举，(Enum<?>) this 转型恒成立。
     */
    default String getName() {
        return ((Enum<?>) this).name();
    }
}
