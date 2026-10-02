package com.litisle.litislereview.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 举报目标类型枚举（report.target_type）
 * <p>1-文章 2-评论 3-用户 4-私信 5-图片/文件（target_type=5 时 target_id = file_meta.id）</p>
 * <p>与 review_task.biz_type 错位：私信 5↔4、图片 6↔5，映射见 Review-Plan §4.5.2 SSOT 表，
 * 禁按 biz_type==target_type 恒等实现</p>
 */
@Getter
public enum TargetTypeEnum {

    /** 文章 */
    ARTICLE(1, "文章"),

    /** 评论 */
    COMMENT(2, "评论"),

    /** 用户 */
    USER(3, "用户"),

    /** 私信 */
    MESSAGE(4, "私信"),

    /** 图片/文件（target_id = file_meta.id） */
    IMAGE(5, "图片/文件");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    TargetTypeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}