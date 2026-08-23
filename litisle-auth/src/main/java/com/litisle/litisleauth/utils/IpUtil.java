package com.litisle.litisleauth.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * 客户端 IP 获取工具类
 *
 * 【设计说明】
 * 支持多级代理：X-Forwarded-For > X-Real-IP > RemoteAddr
 * IP 可信性由 Nginx 层保证，本类只负责解析
 */
public final class IpUtil {

    private IpUtil() {
    }

    /**
     * 获取客户端真实 IP
     *
     * @param request HTTP 请求
     * @return 客户端 IP（获取失败返回 "unknown"）
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        // 1. X-Forwarded-For：标准代理头，多级代理取第一个
        String ip = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        // 2. X-Real-IP：Nginx 反向代理设置的真实 IP
        ip = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        // 3. RemoteAddr：无代理时的直连 IP
        ip = request.getRemoteAddr();
        return StringUtils.hasText(ip) ? ip : "unknown";
    }
}
