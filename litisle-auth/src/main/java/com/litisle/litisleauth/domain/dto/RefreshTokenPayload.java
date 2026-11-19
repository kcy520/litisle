package com.litisle.litisleauth.domain.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Refresh Token Redis 存储载荷（auth:refresh:{refreshToken} 的 value，JSON 序列化）
 *
 * <p>【职责边界】承载一条刷新令牌的全部校验上下文：归属用户、令牌族、绑定设备、设备指纹；
 * 重放墓碑（auth:refresh:used:{token}）仅存 userId 字符串，不使用本类。</p>
 *
 * <p>【兼容语义】历史版本 value 为纯 userId 字符串：解析不以 { 开头时按旧格式降级，
 * familyId/deviceId/fingerprint 留空——刷新轮换时补齐新字段并生成新族，
 * 避免升级窗口期存量令牌全部失效。</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class RefreshTokenPayload {

    /** 归属用户 ID */
    private Long userId;

    /** 令牌族 ID（登录签发生成、轮换沿用；重放/改密码/封禁按族吊销） */
    private String familyId;

    /** 绑定设备 ID（多设备独立族，轮换沿用；用于按族定位反向 key 与设备会话） */
    private String deviceId;

    /** 设备指纹（UA + Accept-Language 的 SHA-256；刷新时比对，不一致 = 盗用信号；历史载荷为 null 时跳过比对） */
    private String fingerprint;
}
