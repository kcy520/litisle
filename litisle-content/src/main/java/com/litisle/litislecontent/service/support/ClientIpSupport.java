package com.litisle.litislecontent.service.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 客户端 IP 解析收口组件（阶段17：原 ArticleController#resolveClientIp 私有逻辑上收），
 * 文章详情与章节详情的匿名浏览去重共用同一份 IP 解析，避免两份实现漂移
 *
 * @author litisle
 */
@Component
public class ClientIpSupport {

    /**
     * 解析客户端真实 IP（X-Forwarded-For 首段优先，兼容网关/反向代理链；空退化 remoteAddr）
     *
     * @param request HTTP 请求
     * @return 客户端 IP（匿名浏览去重基准之一）
     */
    public String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 多级代理链取首段（最原始客户端 IP）
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
