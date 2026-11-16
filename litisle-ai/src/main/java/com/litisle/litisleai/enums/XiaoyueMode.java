package com.litisle.litisleai.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 小悦会话模式枚举（ai_session.mode 字段取值）
 *
 * <p>【职责边界】标识小悦会话的交互模式（润色迭代/结构分析/摘要/深度对话），
 * 仅 XIAOYUE 会话使用；一次性请求不建会话，mode 为空不入库。</p>
 *
 * <p>实现 DictEnum：接入 common-core 三值兼容绑定（name/code/desc 均可作为请求参数）。</p>
 */
@Getter
public enum XiaoyueMode implements DictEnum {

    /** 润色：多轮迭代润色建议 */
    POLISH("POLISH", "润色"),

    /** 结构分析：稿件结构建议 */
    STRUCTURE("STRUCTURE", "结构分析"),

    /** 摘要：稿件摘要建议 */
    SUMMARY("SUMMARY", "摘要"),

    /** 深度对话：通用多轮问答 */
    DEEP("DEEP", "深度对话"),
    ;

    /** 入库值（varchar），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    XiaoyueMode(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
