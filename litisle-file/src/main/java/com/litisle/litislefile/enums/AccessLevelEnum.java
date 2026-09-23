package com.litisle.litislefile.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 访问级别枚举：PRIVATE(0, "私有→预签名 URL") / PUBLIC(1, "公开→直链")
 *
 * 访问方式由 access 决定（一张表支撑直链+预签名两种模式）：
 * - PUBLIC：直链 = public-url-base + "/" + bucket + "/" + path
 *   ⚠️ 用 public-url-base 而非 endpoint：endpoint 是 SDK 内部地址（上传/删除/预签名用），
 *      public-url-base 是给前端的地址——dev 阶段两者相同（都指向 8333），
 *      接 CDN 后 public-url-base 改为 CDN 域名（如 https://img.litisle.com），endpoint 保持内网不变
 * - PRIVATE：SeaweedFsClient.presign(bucket, path, presignExpireMinutes)，默认 30 分钟
 */
@Getter
public enum AccessLevelEnum {

    /** 私有 → 预签名 URL（SeaweedFsClient.presign，默认 30 分钟短时效） */
    PRIVATE(0, "私有"),

    /** 公开 → 直链（public-url-base + bucket + path，CDN 友好） */
    PUBLIC(1, "公开");

    /** 入库值（tinyint），MybatisEnumTypeHandler 按 @EnumValue 映射 */
    @EnumValue
    private final int code;

    /** 前端展示用：JSON 序列化为 desc 而非 code */
    @JsonValue
    private final String desc;

    AccessLevelEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
