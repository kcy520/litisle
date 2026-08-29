package com.litisle.litisleauth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.util.RedisUtil;
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

    // ==================== 私有方法 ====================

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

