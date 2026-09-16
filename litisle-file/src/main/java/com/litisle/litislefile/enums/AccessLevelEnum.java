package com.litisle.litislefile.enums;

/**
 * 访问级别枚举：PRIVATE(0, "私有→预签名 URL") / PUBLIC(1, "公开→直链")
 *
 * 访问方式由 access 决定（一张表支撑直链+预签名两种模式）：
 * - PUBLIC：直链 = endpoint + "/" + bucket + "/" + path（未来换 CDN 只改拼接）
 * - PRIVATE：SeaweedFsClient.presign(bucket, path, presignExpireMinutes)，默认 30 分钟
 */
public enum AccessLevelEnum {
}
