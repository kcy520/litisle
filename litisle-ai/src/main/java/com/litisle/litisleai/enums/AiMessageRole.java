package com.litisle.litisleai.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * AI 消息角色枚举（ai_message.role 字段取值）
 *
 * <p>【职责边界】标识会话消息的产生方，与模型对话的角色语义对齐（user/assistant/system）；
 * 消息落库由 AiSessionService 收口，Agent 不直接写 ai_message。</p>
 *
 * <p>实现 DictEnum：code 为 String 型，接入 common-core 三值兼容绑定（name/code/desc）。</p>
 */
@Getter
public enum AiMessageRole implements DictEnum {

    /** 用户消息 */
    USER("USER", "用户"),

    /** 助手消息（Agent 终答落库） */
    ASSISTANT("ASSISTANT", "助手"),

    /** 系统消息（system prompt 快照等） */
    SYSTEM("SYSTEM", "系统"),
    ;

    /** 入库值（varchar），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    AiMessageRole(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
