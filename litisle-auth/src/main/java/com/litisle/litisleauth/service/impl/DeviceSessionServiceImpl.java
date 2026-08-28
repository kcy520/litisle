package com.litisle.litisleauth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litisleauth.constant.AuthCacheKey;
import com.litisle.litisleauth.domain.vo.DeviceSessionVO;
import com.litisle.litisleauth.service.DeviceSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 在线设备会话服务实现
 * <p>
 * 基于 Redis 实现多设备登录管理，不依赖数据库表。
 *
 * 【Redis 数据结构】
 * <pre>
 * Hash: auth:device:{deviceId}
 *   field        | value
 *   userId       | 用户 ID
 *   deviceName   | 设备名称
 *   ip           | 登录 IP
 *   loginTime    | 登录时间 (ISO)
 *   lastActiveAt | 最后活跃时间 (ISO)
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

    @Override
    public List<DeviceSessionVO> listCurrentUserDevices() {
        // 【实现思路】
        // 1. 获取当前登录 userId：StpUtil.getLoginIdAsLong()
        // 2. 从 Redis 读取 Set：AuthCacheKey.USER_DEVICES + userId
        // 3. 遍历 deviceId，读取 Hash：AuthCacheKey.DEVICE_SESSION + deviceId
        // 4. 当前请求的 deviceId 从请求头获取，匹配时标记 current = true
        // 5. 按 loginTime 降序排序
        // TODO 待实现
        return List.of();
    }

    @Override
    public void kickDevice(String deviceId) {
        // 【实现思路】
        // 1. 检查 deviceId 归属：从 Hash 中读取 userId，校验是否属于当前用户
        // 2. 删除 Hash：redisUtil.delete(AuthCacheKey.DEVICE_SESSION + deviceId)
        // 3. 从 Set 移除：redisUtil.sRem(AuthCacheKey.USER_DEVICES + userId, deviceId)
        // 4. 使 Sa-Token 会话下线：
        //    SaSession session = StpUtil.getSessionByLoginId(userId, false);
        //    if (session != null) session.logoutByTokenValue(token);
        // TODO 待实现
        log.warn("kickDevice 尚未实现: deviceId={}", deviceId);
    }
}

