package com.litisle.commonredis.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备会话信息
 *
 * 【设计说明】
 * 记录单个设备的一次登录会话，作为 Hash 的 value 存储在 Redis 中：
 * - Key   ：auth:session:{userId}
 * - field ：tokenId（JWT 的 jti）
 * - value ：本对象序列化后的 JSON 字符串
 *
 * 【字段设计要点】
 * 1. tokenId 冗余存储：从 Hash value 反序列化后可直接使用，无需再从 field 获取
 * 2. 时间字段全部使用 Long 时间戳（epoch millis）：
 *    - 避免 LocalDateTime 序列化需要 JavaTimeModule 的问题
 *    - 排序比较直接用 long，性能更好
 * 3. tokenExpireTime 用于懒清理：Redis Hash 不支持 field 级 TTL，
 *    读取时根据该字段过滤已过期的会话并 HDEL
 *
 * 【使用场景】
 * - 登录成功：AuthService 构建 DeviceSession → DeviceSessionManager.registerSession()
 * - 设备列表：用户查看"我的在线设备"接口
 * - 踢人依据：loginTime 最早的设备在超限时被优先挤下线
 *
 * @author litisle
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceSession {

    /**
     * JWT 唯一标识（jti）
     * 与 Hash 的 field 一致，冗余存储便于反序列化后直接使用
     */
    private String tokenId;

    /**
     * 设备类型
     * 取值：WEB / APP / MINI_PROGRAM（由 litisle-auth 登录时解析 User-Agent 得出）
     */
    private String deviceType;

    /**
     * 设备名称
     * 例：Chrome on Windows / iPhone 15 / 微信内置浏览器
     * 由登录时解析 User-Agent 得出，用于"在线设备管理"页面展示
     */
    private String deviceName;

    /**
     * 登录 IP
     * 由网关或登录接口从 X-Forwarded-For / remoteAddr 获取，用于安全审计
     */
    private String ip;

    /**
     * 登录时间（epoch millis）
     * 多设备超限时，此值最早的设备被优先挤下线
     */
    private Long loginTime;

    /**
     * Token 过期时间（epoch millis）
     * 【重要】懒清理依据：读取会话列表时，tokenExpireTime < 当前时间的条目视为已过期
     * Redis Hash 不支持 field 级 TTL，通过此字段在业务层实现等效清理
     */
    private Long tokenExpireTime;
}
