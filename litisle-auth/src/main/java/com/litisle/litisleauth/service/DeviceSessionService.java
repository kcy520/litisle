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
     * 登录/刷新令牌时注册或更新当前设备会话
     * <p>
     * 1. deviceId 解析优先级：调用方显式传入（刷新轮换场景沿用旧票绑定设备，保证令牌族设备连续）→
     *    客户端请求头 X-Device-Id → 服务端生成 UUID
     * 2. 设备已存在且归属同一用户（刷新令牌场景）：仅更新 tokenValue 与 lastActiveTime
     * 3. 新设备：写入设备 Hash + 加入用户设备索引 Set + 执行设备数量上限检查
     *
     * @param userId           登录用户 ID
     * @param tokenValue       本次登录签发的 accessToken
     * @param expectedDeviceId 调用方显式指定的设备 ID（登录场景传 null，走请求头/服务端生成）
     * @return deviceId（随 TsuccessenVO 回传前端）
     */
    String registerDevice(Long userId, String tokenValue, String expectedDeviceId);

    /**
     * 退出登录时清理当前设备会话记录（尽力而为）
     * <p>
     * 校验设备归属后删除设备 Hash 与用户设备索引 Set 成员。
     *
     * @param userId 当前登录用户 ID
     */
    void removeCurrentDevice(Long userId);

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

    /**
     * 强制下线指定用户的全部在线设备
     * <p>
     * 1. 读取 Redis 集合 auth:user:devices:{userId} 获取全部 deviceId
     * 2. 批量删除各设备 Hash auth:device:{deviceId}
     * 3. 删除用户设备索引 Set
     * 4. 调用 Sa-Token 注销该账号全部会话（StpUtil.logout(userId)）
     * <p>
     * 供管理员注销/封禁用户时清理在线状态使用（尽力而为）。
     *
     * @param userId 目标用户 ID
     */
    void kickAllDevices(Long userId);
}

