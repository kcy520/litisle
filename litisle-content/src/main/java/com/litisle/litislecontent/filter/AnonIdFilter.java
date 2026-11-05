package com.litisle.litislecontent.filter;

import com.litisle.litislecontent.constant.FeedConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 匿名标识过滤器（批次十 10.2，对齐已确认决策第 14 条）
 *
 * <p>作用：只对推荐流两条新流端点做「请求头 {@code X-Anon-Id} 格式校验 + 缺失/非法兜底生成」。
 * 校验通过或生成后写入请求属性供 Controller 读取；仅在服务端生成时才把标识写回响应头，
 * 前端收到后存 localStorage，实现同一浏览器跨会话稳定。</p>
 *
 * <p>边界：匿名标识不是身份凭证，只作曝光去重/快照隔离的 key；不做 Cookie 签发、不做 HMAC 签名。
 * 频控仍按 IP 维度，不依赖本标识（防匿名刷量绕过）。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class AnonIdFilter extends OncePerRequestFilter {

    /** 需要匿名标识的路径前缀：文章流/书籍流（旧 /api/content/feed 不在此前缀内，保持登录态） */
    private static final String FEED_STREAM_PATH_PREFIX = "/api/content/feed/";

    /**
     * 过滤主流程：命中推荐流路径时校验/兜底匿名标识，其余路径直接放行
     *
     * @param request     HTTP 请求
     * @param response    HTTP 响应（服务端生成标识时写回响应头）
     * @param filterChain 过滤器链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 步骤1：只处理推荐流两条新流端点，其他接口零侵入
        if (!request.getRequestURI().startsWith(FEED_STREAM_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 步骤2：取客户端携带的匿名标识
        String anonId = request.getHeader(FeedConstant.ANON_ID_HEADER);

        // 步骤3：格式校验（仅接受 UUID v4）；缺失或非法则服务端生成
        boolean generated = !isValidAnonId(anonId);
        if (generated) {
            anonId = UUID.randomUUID().toString();
            // 兜底生成时写回响应头，前端据此写回 localStorage（合法入参不回写，避免无意义响应头）
            response.setHeader(FeedConstant.ANON_ID_HEADER, anonId);
            log.debug("匿名标识缺失或非法，服务端兜底生成: anonId={}, uri={}", anonId, request.getRequestURI());
        }

        // 步骤4：写入请求属性，Controller 直接读取，避免业务层重复解析请求头
        request.setAttribute(FeedConstant.ANON_ID_ATTRIBUTE, anonId);

        filterChain.doFilter(request, response);
    }

    /**
     * 校验匿名标识是否为合法 UUID v4
     *
     * @param anonId 请求头携带的标识，可为空
     * @return true 表示格式合法，可直接使用
     */
    private boolean isValidAnonId(String anonId) {
        // 空值直接判非法，触发服务端兜底生成
        return StringUtils.hasText(anonId) && FeedConstant.ANON_ID_PATTERN.matcher(anonId).matches();
    }
}
