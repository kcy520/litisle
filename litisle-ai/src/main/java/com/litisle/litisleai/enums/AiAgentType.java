package com.litisle.litisleai.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * AI Agent 类型枚举（ai_session.agent_type 字段取值）
 *
 * <p>【职责边界】小悦（生成式对话/建议 Agent）与小审（判定辅助 Agent）共用 ai_session 表，
 * 本枚举标识会话归属的 Agent 底座；新 Agent 类型在此扩展，禁止散落字符串常量。</p>
 *
 * <p>实现 DictEnum：接入 common-core 三值兼容绑定（name/code/desc 均可作为请求参数）。</p>
 */
@Getter
public enum AiAgentType implements DictEnum {

    /** 小悦：生成式对话/建议 Agent（摘要/标签/润色/结构分析/问答） */
    XIAOYUE("XIAOYUE", "小悦"),

    /** 小审：判定辅助 Agent（审核员建议，不自动裁决） */
    XIAOSHEN("XIAOSHEN", "小审"),
    ;

    /** 入库值（varchar），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    AiAgentType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
