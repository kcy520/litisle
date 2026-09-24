package com.litisle.litislefile.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.litisle.commoncore.enums.DictEnum;
import lombok.Getter;

/**
 * 业务类型枚举（决定 bucket/collection 归属）：
 * AVATAR("avatar")   → 头像，默认 PUBLIC（直链，CDN 友好）
 * COVER("cover")     → 封面，默认 PUBLIC
 * ARTICLE_IMG("article") → 正文图，默认 PUBLIC
 * LICENSE("license") → 授权文件，强制 PRIVATE（仅预签名 30 分钟访问，服务层覆盖）
 *
 * code 与 FileProperties.collections 映射一致，用于 Service 层确定 bucket
 * 实现 DictEnum：接入 common-core 三值兼容绑定（name/code/desc 均可作为请求参数，
 * 修复 @JsonValue(desc) 下拉框与 MVC name-only 绑定口径不一致问题）
 */
@Getter
public enum BizTypeEnum implements DictEnum {

    /** 头像，默认 PUBLIC（直链，CDN 友好） */
    AVATAR("avatar", "头像"),

    /** 封面，默认 PUBLIC */
    COVER("cover", "封面"),

    /** 正文图，默认 PUBLIC（高曝光，safe_flag 闸口仅 1 放行） */
    ARTICLE_IMG("article", "正文图"),

    /** 授权文件，强制 PRIVATE（仅预签名 30 分钟访问，服务层覆盖） */
    LICENSE("license", "授权文件");

    /** 入库值（varchar(20)），与 FileProperties.collections 映射一致，Service 层据此确定 bucket */
    @EnumValue
    private final String code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    BizTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
