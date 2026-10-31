package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 词库变更操作类型枚举（wordlib_change_audit.op_type）
 * <p>单条变更逐词审计（ADD/UPDATE/DELETE）；批量导入聚合 1 条（BATCH_IMPORT + word_count）</p>
 * <p>code 为 String（入库 varchar），接入 DictEnum 获得 code/desc/name 三值绑定</p>
 */
@Getter
public enum WordlibOpTypeEnum implements DictEnum {

    /** 新增敏感词（单条） */
    ADD("ADD", "新增"),

    /** 修改敏感词（含启停/黑白切换/改词/升级，单条） */
    UPDATE("UPDATE", "修改"),

    /** 删除敏感词（单条，按 id 或按内容） */
    DELETE("DELETE", "删除"),

    /** 批量导入（聚合 1 条：词清单在备份文件里不重复落库，word_count 记净新增数） */
    BATCH_IMPORT("BATCH_IMPORT", "批量导入");

    /** 入库值（varchar），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    WordlibOpTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
