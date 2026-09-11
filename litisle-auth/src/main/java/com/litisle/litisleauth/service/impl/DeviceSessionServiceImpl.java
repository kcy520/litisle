package com.litisle.litisleauth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litisleauth.config.AuthProperties;
import com.litisle.litisleauth.constant.AuthCacheKey;
import com.litisle.litisleauth.domain.vo.DeviceSessionVO;
import com.litisle.litisleauth.service.DeviceSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 在线设备会话服务实现
 * <p>
 * 基于 Redis 实现多设备登录管理，不依赖数据库表。
 *
 * 【Redis 数据结构】
 * <pre>
 * Hash: auth:device:{deviceId}
 *   field        | value
 *   userId        | 用户 ID
 *   tokenValue    | 设备 token 值（用于踢出时下线对应会话）
 *   deviceName   | 设备名称
 *   ip           | 登录 IP
 *   loginTime    | 登录时间 (ISO)
 *   lastActiveTime | 最后活跃时间 (ISO)
 *
 * Set:  auth:user:devices:{userId}
 *   member: deviceId
 * </pre>
 *
 * 【注意事项】
 * 1. deviceId 在登录时生成（UUID），随 refreshToken 一起返回给客户端
 * 2. 客户端后续请求应在 Header 中携带 deviceId，由过滤器更新 lastActiveAt
 * 3. 设备总数量上限由 AuthProperties 配置（默认 5 台），超限时踢出最旧设备
 *
 * @author litisle
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceSessionServiceImpl implements DeviceSessionService {

    private final RedisUtil redisUtil;

    private final AuthProperties authProperties;

    private final HttpServletRequest request;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * 获取当前账号的在线设备列表
     * <p>
     * 从 Redis 查询 auth:user:devices:{userId} 集合，遍历每个 deviceId 获取设备详情。
     * 当前设备（即发起请求的设备）的 current 标记为 true。
     *
     * @return 在线设备列表，按登录时间降序排列
     */
    @Override
    public List<DeviceSessionVO> listCurrentUserDevices() {
        // 1. 获取当前登录 userId：StpUtil.getLoginIdAsLong()
        Long userId = StpUtil.getLoginIdAsLong();

        // 2. 从 Redis 读取 Set：AuthCacheKey.USER_DEVICES + userId
        Set<String> deviceIds = redisUtil.sMembers(AuthCacheKey.USER_DEVICES + userId);
        if (deviceIds.isEmpty()) {
            return List.of();
        }

        // 3. 获取当前请求的设备ID（从请求头），用于标记 current
        String currentDeviceId = request.getHeader(HeaderConstant.DEVICE_ID);

        // 4. 遍历 deviceId，读取 Hash 获取设备详情
        List<DeviceSessionVO> list = new ArrayList<>();
        for (String deviceId : deviceIds) {
            Map<String, Object> deviceMap = redisUtil.hGetAll(AuthCacheKey.DEVICE_SESSION + deviceId);
            if (deviceMap.isEmpty()) {
                // 脏数据：Hash 已被删除但 Set 中残留，清理
                redisUtil.sRemove(AuthCacheKey.USER_DEVICES + userId, deviceId);
                log.warn("清理脏数据: deviceId={} 的 Hash 已丢失", deviceId);
                continue;
            }

            DeviceSessionVO vo = new DeviceSessionVO()
                .setDeviceId(deviceId)
                .setDeviceName((String) deviceMap.get(AuthCacheKey.DeviceHashField.DEVICE_NAME))
                .setIp((String) deviceMap.get(AuthCacheKey.DeviceHashField.IP))
                .setLoginTime(parseDateTime((String) deviceMap.get(AuthCacheKey.DeviceHashField.LOGIN_TIME)))
                .setLastActiveTime(parseDateTime((String) deviceMap.get(AuthCacheKey.DeviceHashField.LAST_ACTIVE_TIME)))
                .setCurrent(deviceId.equals(currentDeviceId));
            list.add(vo);
        }

        // 5. 按 loginTime 降序排序（最新登录的设备排最前）
        list.sort((a, b) -> {
            if (a.getLoginTime() == null && b.getLoginTime() == null) return 0;
            if (a.getLoginTime() == null) return 1;
            if (b.getLoginTime() == null) return -1;
            return b.getLoginTime().compareTo(a.getLoginTime());
        });

        return list;
    }

    /**
     * 登录/刷新令牌时注册或更新当前设备会话
     * <p>
     * deviceId 优先取客户端请求头 X-Device-Id，未携带则服务端生成 UUID。
     * 设备已存在且归属同一用户（刷新令牌场景）时仅更新 tokenValue 与 lastActiveTime；
     * 新设备则完整写入 Hash 并执行设备数量上限检查（超限踢出最旧设备）。
     *
     * @param userId     登录用户 ID
     * @param tokenValue 本次登录签发的 accessToken
     * @return deviceId
     */
    @Override
    public String registerDevice(Long userId, String tokenValue) {
        // 1. 解析 deviceId：客户端请求头优先，未携带则服务端生成
        String deviceId = request.getHeader(HeaderConstant.DEVICE_ID);
        if (deviceId == null || deviceId.isBlank()) {
            deviceId = UUID.randomUUID().toString().replace("-", "");
        }

        String deviceKey = AuthCacheKey.DEVICE_SESSION + deviceId;
        String userDevicesKey = AuthCacheKey.USER_DEVICES + userId;
        String now = LocalDateTime.now().format(DT_FMT);

        // 2. 设备已存在且归属同一用户：仅更新 tokenValue 与最后活跃时间（刷新令牌场景）
        Map<String, Object> existing = redisUtil.hGetAll(deviceKey);
        if (!existing.isEmpty()
                && String.valueOf(existing.get(AuthCacheKey.DeviceHashField.USER_ID))
                        .equals(String.valueOf(userId))) {
            redisUtil.hSet(deviceKey, AuthCacheKey.DeviceHashField.TOKEN_VALUE, tokenValue);
            redisUtil.hSet(deviceKey, AuthCacheKey.DeviceHashField.LAST_ACTIVE_TIME, now);
            redisUtil.expire(deviceKey, authProperties.getRefreshTokenExpire(), TimeUnit.SECONDS);
            redisUtil.sAdd(userDevicesKey, deviceId);
            return deviceId;
        }

        // 3. 设备被其他账号占用（同一物理设备换号登录）：从原账号设备索引中移除
        if (!existing.isEmpty()) {
            String ownerUserId = (String) existing.get(AuthCacheKey.DeviceHashField.USER_ID);
            if (ownerUserId != null) {
                redisUtil.sRemove(AuthCacheKey.USER_DEVICES + ownerUserId, deviceId);
            }
        }

        // 4. 新设备：写入设备 Hash（设备名从 User-Agent 解析，IP 优先取网关透传头）
        redisUtil.hSet(deviceKey, AuthCacheKey.DeviceHashField.USER_ID, String.valueOf(userId));
        redisUtil.hSet(deviceKey, AuthCacheKey.DeviceHashField.TOKEN_VALUE, tokenValue);
        redisUtil.hSet(deviceKey, AuthCacheKey.DeviceHashField.DEVICE_NAME,
                resolveDeviceName(request.getHeader("User-Agent")));
        redisUtil.hSet(deviceKey, AuthCacheKey.DeviceHashField.IP, resolveIp());
        redisUtil.hSet(deviceKey, AuthCacheKey.DeviceHashField.LOGIN_TIME, now);
        redisUtil.hSet(deviceKey, AuthCacheKey.DeviceHashField.LAST_ACTIVE_TIME, now);
        redisUtil.expire(deviceKey, authProperties.getRefreshTokenExpire(), TimeUnit.SECONDS);

        // 5. 加入用户设备索引 Set，并执行设备数量上限检查
        redisUtil.sAdd(userDevicesKey, deviceId);
        evictOldestDeviceIfExceeded(userId, deviceId);

        log.info("设备会话注册完成: userId={}, deviceId={}", userId, deviceId);
        return deviceId;
    }

    /**
     * 退出登录时清理当前设备会话记录（尽力而为）
     * <p>
     * 校验设备归属后删除设备 Hash 与用户设备索引 Set 成员，
     * 设备不存在或归属不符时静默跳过，不影响退出主流程。
     *
     * @param userId 当前登录用户 ID
     */
    @Override
    public void removeCurrentDevice(Long userId) {
        // 1. 读取当前请求设备 ID，未携带则无需清理
        String deviceId = request.getHeader(HeaderConstant.DEVICE_ID);
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }

        // 2. 校验设备归属后删除记录（容忍脏数据）
        Map<String, Object> deviceMap = redisUtil.hGetAll(AuthCacheKey.DEVICE_SESSION + deviceId);
        if (deviceMap.isEmpty()) {
            return;
        }
        String ownerUserId = (String) deviceMap.get(AuthCacheKey.DeviceHashField.USER_ID);
        if (ownerUserId != null && ownerUserId.equals(String.valueOf(userId))) {
            redisUtil.delete(AuthCacheKey.DEVICE_SESSION + deviceId);
            redisUtil.sRemove(AuthCacheKey.USER_DEVICES + userId, deviceId);
            log.debug("已清理当前设备会话记录: userId={}, deviceId={}", userId, deviceId);
        }
    }


    /**
     * 踢出指定设备
     * <p>
     * 1. 从 Redis 查询 device Hash 获取 userId
     * 2. 校验 deviceId 归属：是否属于当前登录用户
     * 3. 删除 auth:device:{deviceId} Hash
     * 4. 从 auth:user:devices:{userId} Set 移除 deviceId
     * 5. 调用 Sa-Token 的 logoutByTokenValue 使该设备会话下线
     *
     * @param deviceId 设备唯一标识
     * @throws BizException 设备不存在或无权限操作时抛出
     */
    @Override
    public void kickDevice(String deviceId) {
        // 1. 从 Redis 查询 device Hash 获取 userId
        Map<String, Object> deviceMap = redisUtil.hGetAll(AuthCacheKey.DEVICE_SESSION + deviceId);
        if (deviceMap.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "设备不存在或已离线");
        }

        // 2. 检查 deviceId 归属：从 Hash 中读取 userId，校验是否属于当前用户
        Long userId = Long.valueOf((String) deviceMap.get(AuthCacheKey.DeviceHashField.USER_ID));
        Long currentUserId = StpUtil.getLoginIdAsLong();
        if (!userId.equals(currentUserId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作此设备");
        }

        // 3. 使 Sa-Token 会话下线：通过 Device Hash 中存储的 tokenValue 精确下线该设备
        String tokenValue = (String) deviceMap.get(AuthCacheKey.DeviceHashField.TOKEN_VALUE);

        // 4. 删除 删除设备 Hash：redisUtil.delete(AuthCacheKey.DEVICE_SESSION + deviceId)  这个设备的所有信息全部消失
        redisUtil.delete(AuthCacheKey.DEVICE_SESSION + deviceId);

        // 5. 从 Set 移除：redisUtil.sRemove(AuthCacheKey.USER_DEVICES + userId, deviceId)  从用户的设备 Set 中移除 删 Redis Set 成员（清用户设备索引）
        redisUtil.sRemove(AuthCacheKey.USER_DEVICES + userId, deviceId);


        if (tokenValue != null) {
            try {
                StpUtil.logoutByTokenValue(tokenValue);
                log.info("设备踢出成功: deviceId={}, userId={}, token已注销", deviceId, userId);
            } catch (Exception e) {
                // tokenValue 可能已过期或被其他操作先行注销，不影响核心逻辑
                log.warn("设备踢出时注销 token 异常（可能已过期）: deviceId={}, userId={}, error={}",
                        deviceId, userId, e.getMessage());
            }
        } else {
            log.warn("设备踢出时未找到 tokenValue（可能未存储）: deviceId={}, userId={}", deviceId, userId);
        }

        log.info("设备踢出完成: deviceId={}, userId={}", deviceId, userId);
    }

    /**
     * 强制下线指定用户的全部在线设备
     * <p>
     * 用于管理员注销/封禁用户时清理其在线的所有会话与设备记录。
     * 逐个删除设备 Hash 与用户设备索引，最后注销该账号全部 Sa-Token 会话。
     * 删除类操作具幂等性，脏数据（Hash 已丢但 Set 残留）会被一并清理。
     *
     * @param userId 目标用户 ID
     */
    @Override
    public void kickAllDevices(Long userId) {
        // 1. 读取该用户全部在线设备 ID
        Set<String> deviceIds = redisUtil.sMembers(AuthCacheKey.USER_DEVICES + userId);
        if (deviceIds.isEmpty()) {
            log.info("用户无在线设备，跳过清理: userId={}", userId);
            return;
        }

        // 2. 批量删除各设备 Hash + 清设备索引 Set
        for (String deviceId : deviceIds) {
            redisUtil.delete(AuthCacheKey.DEVICE_SESSION + deviceId);
        }
        redisUtil.delete(AuthCacheKey.USER_DEVICES + userId);

        // 3. 注销该账号全部 Sa-Token 会话（覆盖所有 token）
        try {
            StpUtil.logout(userId);
        } catch (Exception e) {
            // 会话可能已过期或已被注销，不影响核心逻辑
            log.warn("注销账号会话异常（可能已下线）: userId={}, error={}", userId, e.getMessage());
        }

        log.info("强制下线完成: userId={}, 清理设备数={}", userId, deviceIds.size());
    }

    // ==================== 私有方法 ====================

    /**
     * 设备数量超限检查：超过 maxDevices 时按 loginTime 踢出最旧设备（排除当前设备）
     *
     * @param userId          用户 ID
     * @param currentDeviceId 当前刚注册的设备 ID（不参与淘汰）
     */
    private void evictOldestDeviceIfExceeded(Long userId, String currentDeviceId) {
        // 1. 读取用户全部在线设备
        Set<String> deviceIds = redisUtil.sMembers(AuthCacheKey.USER_DEVICES + userId);
        int maxDevices = authProperties.getMaxDevices();
        if (deviceIds.size() <= maxDevices) {
            return;
        }

        // 2. 按 loginTime 升序找出最旧设备（Hash 丢失或时间无法解析的脏数据优先清理）
        String oldestDeviceId = null;
        LocalDateTime oldestTime = null;
        for (String deviceId : deviceIds) {
            if (deviceId.equals(currentDeviceId)) {
                continue;
            }
            Map<String, Object> deviceMap = redisUtil.hGetAll(AuthCacheKey.DEVICE_SESSION + deviceId);
            String loginTimeStr = deviceMap.isEmpty()
                    ? null : (String) deviceMap.get(AuthCacheKey.DeviceHashField.LOGIN_TIME);
            LocalDateTime loginTime = parseDateTime(loginTimeStr);
            if (loginTime == null) {
                oldestDeviceId = deviceId;
                break;
            }
            if (oldestTime == null || loginTime.isBefore(oldestTime)) {
                oldestTime = loginTime;
                oldestDeviceId = deviceId;
            }
        }

        // 3. 踢出最旧设备
        if (oldestDeviceId != null) {
            log.info("设备数超限，踢出最旧设备: userId={}, deviceId={}, max={}",
                    userId, oldestDeviceId, maxDevices);
            removeDeviceRecord(oldestDeviceId);
        }
    }

    /**
     * 删除设备记录并注销其会话（供超限踢出使用，尽力而为）
     *
     * @param deviceId 目标设备 ID
     */
    private void removeDeviceRecord(String deviceId) {
        // 1. 读取设备 Hash 中的 tokenValue 与归属用户（可能已丢失）
        Map<String, Object> deviceMap = redisUtil.hGetAll(AuthCacheKey.DEVICE_SESSION + deviceId);
        String tokenValue = deviceMap.isEmpty()
                ? null : (String) deviceMap.get(AuthCacheKey.DeviceHashField.TOKEN_VALUE);
        String ownerUserId = deviceMap.isEmpty()
                ? null : (String) deviceMap.get(AuthCacheKey.DeviceHashField.USER_ID);

        // 2. 删除设备 Hash 并从归属用户的设备索引 Set 中移除
        redisUtil.delete(AuthCacheKey.DEVICE_SESSION + deviceId);
        if (ownerUserId != null) {
            redisUtil.sRemove(AuthCacheKey.USER_DEVICES + ownerUserId, deviceId);
        }

        // 3. 注销该设备 token（尽力而为，已过期不影响核心逻辑）
        if (tokenValue != null) {
            try {
                StpUtil.logoutByTokenValue(tokenValue);
            } catch (Exception e) {
                log.warn("超限踢出设备时注销 token 异常（可能已过期）: deviceId={}, error={}",
                        deviceId, e.getMessage());
            }
        }
    }

    /**
     * 从 User-Agent 解析设备名称（简化版，主流浏览器识别）
     *
     * @param userAgent User-Agent 请求头，可能为 null
     * @return 设备名称
     */
    private String resolveDeviceName(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "未知设备";
        }
        if (userAgent.contains("Edg/")) {
            return "Edge 浏览器";
        }
        if (userAgent.contains("Chrome/")) {
            return "Chrome 浏览器";
        }
        if (userAgent.contains("Firefox/")) {
            return "Firefox 浏览器";
        }
        if (userAgent.contains("Safari/")) {
            return "Safari 浏览器";
        }
        return userAgent.length() > 32 ? userAgent.substring(0, 32) : userAgent;
    }

    /**
     * 解析客户端真实 IP：优先网关透传头，回退直连地址
     *
     * @return 客户端 IP
     */
    private String resolveIp() {
        // 1. 网关透传的 X-Forwarded-For 首段为原始客户端 IP
        String xff = request.getHeader(HeaderConstant.X_FORWARDED_FOR);
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }

        // 2. 依次回退 X-Real-IP、直连地址
        String realIp = request.getHeader(HeaderConstant.X_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * 解析 ISO 日期时间字符串为 LocalDateTime
     *
     * @param dateStr 日期时间字符串，可能为 null 或空
     * @return LocalDateTime，解析失败时返回 null
     */
    private LocalDateTime parseDateTime(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateStr, DT_FMT);
        } catch (Exception e) {
            log.warn("日期时间解析失败: {}", dateStr);
            return null;
        }
    }

}

