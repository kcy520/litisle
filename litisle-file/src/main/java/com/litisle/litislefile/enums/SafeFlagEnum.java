package com.litisle.litislefile.enums;


import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 违规审核四态枚举（default-deny）
 * 0-违规（禁止访问）1-正常 2-待检测（默认）3-可疑（机审命中，待人审复核）
 *
 * 设计要点：放行以"检测完成"为前提，而非"未检出违规"，
 * 避免异步检测窗口内未检图被放行（seaweedfs笔记.md §3.6/§3.6.3）；
 * 闸口按曝光面分层：ARTICLE_IMG/COVER 仅 NORMAL 放行、PENDING 展示占位图；
 * AVATAR/LICENSE 的 PENDING 放行、检出违规置 VIOLATIVE 并强制刷新
 */
@Getter
public enum SafeFlagEnum {

    /** 违规（禁止访问，强制刷新已发 URL） */
    VIOLATIVE(0, "违规"),

    /** 正常（检测通过，全部场景放行） */
    NORMAL(1, "正常"),

    /** 待检测（默认；高曝光场景展示占位图，低曝光场景放行） */
    PENDING(2, "待检测"),

    /** 可疑（机审命中待人审复核；人审终审前对外按占位处理，不对外直发） */
    SUSPICIOUS(3, "可疑");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    SafeFlagEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}