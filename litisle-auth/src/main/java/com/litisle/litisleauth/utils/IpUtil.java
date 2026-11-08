package com.litisle.litisleauth.utils;

import com.litisle.commoncore.constant.ClientIpConstant;
import com.litisle.commoncore.constant.HeaderConstant;
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
     * @return 客户端 IP（获取失败返回 {@link ClientIpConstant#UNKNOWN_IP}）
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return ClientIpConstant.UNKNOWN_IP;
        }
        // 1. X-Forwarded-For：标准代理头，多级代理取第一个
        String ip = request.getHeader(HeaderConstant.X_FORWARDED_FOR);
        if (StringUtils.hasText(ip) && !ClientIpConstant.UNKNOWN_IP.equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        // 2. X-Real-IP：Nginx 反向代理设置的真实 IP
        ip = request.getHeader(HeaderConstant.X_REAL_IP);
        if (StringUtils.hasText(ip) && !ClientIpConstant.UNKNOWN_IP.equalsIgnoreCase(ip)) {
            return ip;
        }
        // 3. RemoteAddr：无代理时的直连 IP
        ip = request.getRemoteAddr();
        return StringUtils.hasText(ip) ? ip : ClientIpConstant.UNKNOWN_IP;
    }
}
