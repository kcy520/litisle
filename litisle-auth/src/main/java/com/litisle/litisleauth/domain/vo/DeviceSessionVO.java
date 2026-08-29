package com.litisle.litisleauth.domain.vo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 在线设备会话 VO
 * <p>
 * 展示当前账号已登录的设备列表，供前端管理多设备登录用。
 *
 * 【设计说明】
 * 1. 设备信息存储在 Redis（Hash 结构），key = auth:device:{deviceId}
 * 2. 每次登录时生成 deviceId（UUID），关联到 userId 的在线设备集合
 * 3. 退出登录或踢出设备时删除对应记录，Sa-Token 会话一并失效
 *
 * @author litisle
 */
@Accessors(chain = true)
@Data
public class DeviceSessionVO {

    /** 设备唯一标识 */
    private String deviceId;

    /** 设备名称（如 Chrome 120.0 / iOS App 3.2.1） */
    private String deviceName;

    /** 登录 IP */
    private String ip;

    /** 登录时间 */
    private LocalDateTime loginTime;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveTime;

    /** 是否为当前使用的设备 */
    private Boolean current;
}

