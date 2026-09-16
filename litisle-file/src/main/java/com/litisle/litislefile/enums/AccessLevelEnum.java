package com.litisle.litislefile.enums;

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
public enum AccessLevelEnum {
}
