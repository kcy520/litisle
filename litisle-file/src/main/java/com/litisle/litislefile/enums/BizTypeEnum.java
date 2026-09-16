package com.litisle.litislefile.enums;

/**
 * 业务类型枚举（决定 bucket/collection 归属）：
 * AVATAR("avatar")   → 头像，默认 PUBLIC（直链，CDN 友好）
 * COVER("cover")     → 封面，默认 PUBLIC
 * ARTICLE_IMG("article") → 正文图，默认 PUBLIC
 * LICENSE("license") → 授权文件，强制 PRIVATE（仅预签名 30 分钟访问，服务层覆盖）
 *
 * code 与 FileProperties.collections 映射一致，用于 Service 层确定 bucket
 */
public enum BizTypeEnum {
}
