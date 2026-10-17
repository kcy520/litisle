package com.litisle.litislecontent.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.IntDictEnum;
import lombok.Getter;

/**
 * 正文图片对齐枚举：LEFT(1)/LEFT_CENTER(2)/CENTER(3)/RIGHT_CENTER(4)/RIGHT(5)
 * <p>归属约定（跨模块）：本枚举为权威来源（content 库 article_image.align），
 * file 库 file_meta.align 仅作文件维度冗余</p>
 */
@Getter
public enum AlignEnum implements IntDictEnum {

    /** 左对齐 */
    LEFT(1, "左"),

    /** 左偏中 */
    LEFT_CENTER(2, "左偏中"),

    /** 居中（默认） */
    CENTER(3, "中"),

    /** 中偏右 */
    RIGHT_CENTER(4, "中偏右"),

    /** 右对齐 */
    RIGHT(5, "右");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    AlignEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}