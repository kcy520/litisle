package com.litisle.litisleauth.service;

import com.litisle.litisleauth.domain.vo.DeviceSessionVO;

import java.util.List;

/**
 * 在线设备会话服务接口
 * <p>
 * 管理用户的多设备登录会话，支持查看在线设备列表、踢出指定设备。
 *
 * 【实现思路】
 * 1. 设备信息存储于 Redis：
 *    - Hash: auth:device:{deviceId} → {userId, deviceName, ip, loginTime, lastActiveTime}
 *    - Set:  auth:user:devices:{userId} → {deviceId1, deviceId2, ...}
 * 2. 登录时在 buildTsuccessenResponse 中生成 deviceId 并写入 Redis
 * 3. 每次请求通过 Sa-Token 拦截器更新 lastActiveTime
 * 4. 踢出设备时：删除 device Hash + 从 user Set 移除 + 清除 Sa-Token 会话
 *
 * @author litisle
 */
public interface DeviceSessionService {

    /**
     * 获取当前账号的在线设备列表
     * <p>
     * 从 Redis 查询 auth:user:devices:{userId} 集合，遍历每个 deviceId 获取设备详情。
     * 当前设备（即发起请求的设备）的 current 标记为 true。
     *
     * @return 在线设备列表，按登录时间降序排列
     */
    List<DeviceSessionVO> listCurrentUserDevices();

    /**
     * 踢出指定设备
     * <p>
     * 1. 从 Redis 查询 device Hash 获取 userId
     * 2. 删除 auth:device:{deviceId}
     * 3. 从 auth:user:devices:{userId} 移除 deviceId
     * 4. 调用 Sa-Token 的会话管理 API 使该设备下线
     *
     * @param deviceId 设备唯一标识
     * @throws com.litisle.commoncore.exception.BizException 设备不存在或无权限操作时抛出
     */
    void kickDevice(String deviceId);
}

