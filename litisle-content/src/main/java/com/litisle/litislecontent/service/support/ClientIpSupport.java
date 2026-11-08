package com.litisle.litislecontent.service.support;

import com.litisle.commoncore.constant.ClientIpConstant;
import com.litisle.commoncore.constant.HeaderConstant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 客户端 IP 解析收口组件（阶段17：原 ArticleController#resolveClientIp 私有逻辑上收），
 * 文章详情、章节详情与推荐流匿名限流共用同一份 IP 解析，避免多份实现口径漂移
 *
 * <p>统一口径（15.2-A）：X-Forwarded-For 首段 → X-Real-IP → RemoteAddr → {@link ClientIpConstant#UNKNOWN_IP}</p>
 *
 * @author litisle
 */
@Component
public class ClientIpSupport {

    /**
     * 解析客户端真实 IP（兼容网关/反向代理链，逐级回退到统一兜底值）
     *
     * @param request HTTP 请求
     * @return 客户端 IP（匿名浏览去重/匿名限流基准之一）
     */
    public String resolveClientIp(HttpServletRequest request) {
        // 无请求上下文（如异步任务）时直接返回统一兜底值，避免调用方拿到 null
        if (request == null) {
            return ClientIpConstant.UNKNOWN_IP;
        }
        // 1. X-Forwarded-For：标准代理头，多级代理链取首段（最原始客户端 IP）
        String ip = request.getHeader(HeaderConstant.X_FORWARDED_FOR);
        if (StringUtils.hasText(ip)) {
            ip = ip.split(",")[0].trim();
        }
        // 2. 代理头无效时回退 X-Real-IP（Nginx 反向代理设置的真实 IP）
        if (!StringUtils.hasText(ip) || ClientIpConstant.UNKNOWN_IP.equalsIgnoreCase(ip)) {
            ip = request.getHeader(HeaderConstant.X_REAL_IP);
        }
        // 3. 仍无效则取直连地址 RemoteAddr
        if (!StringUtils.hasText(ip) || ClientIpConstant.UNKNOWN_IP.equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 4. 全部取不到时统一兜底，保证同一请求在各模块得到相同的 IP 标识
        return StringUtils.hasText(ip) ? ip : ClientIpConstant.UNKNOWN_IP;
    }
}
