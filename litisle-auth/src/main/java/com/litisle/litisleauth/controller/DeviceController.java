package com.litisle.litisleauth.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleauth.domain.vo.DeviceSessionVO;
import com.litisle.litisleauth.service.DeviceSessionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 在线设备管理控制器
 * <p>
 * 提供当前账号在线设备列表查询和踢出指定设备的能力。
 * 所有接口需登录后访问（@SaCheckLogin）。
 *
 * 【设计说明】
 * 1. 设备信息存储于 Redis，不落数据库，避免频繁写库
 * 2. 设备上限（默认 5 台）在登录时校验，超限自动踢出最旧设备
 * 3. 基于 Sa-Token 的会话管理实现下线，无需额外 Token 黑名单
 *
 * @author litisle
 */
@RestController
@RequestMapping("/api/auth/devices")
@SaCheckLogin
public class DeviceController {

    private final DeviceSessionService deviceSessionService;

    public DeviceController(DeviceSessionService deviceSessionService) {
        this.deviceSessionService = deviceSessionService;
    }

    /**
     * 当前账号在线设备列表
     * <p>
     * 返回当前登录账号的所有在线设备，包括设备名称、IP、登录时间、最后活跃时间。
     * 当前发起请求的设备标记为 current = true，便于前端识别。
     *
     * @return 在线设备列表（按登录时间降序）
     */
    @GetMapping
    public Result<List<DeviceSessionVO>> list() {
        return Result.success(deviceSessionService.listCurrentUserDevices());
    }

    /**
     * 踢出指定设备
     * <p>
     * 删除指定设备的 Redis 记录，并使其 Sa-Token 会话立即下线。
     * 不可踢出当前正在使用的设备（前端应自行控制）。
     *
     * @param deviceId 设备唯一标识（从列表接口获取）
     */
    @DeleteMapping("/{deviceId}")
    public Result<Void> kick(@PathVariable String deviceId) {
        deviceSessionService.kickDevice(deviceId);
        return Result.success();
    }
}