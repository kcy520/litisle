package com.litisle.litislegateway.filter;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.constant.HeaderConstant;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collection;

/**
 * 网关用户上下文透传过滤器
 *
 * 【设计说明】
 * 1. SaReactorFilter 完成 JWT 认证后，本过滤器将用户信息注入下游请求 Header
 * 2. 下游业务服务从 Header 读取用户信息（HeaderConstant），写入自己的 Sa-Token 上下文
 * 3. 白名单路径（未登录）不注入用户 Header，直接放行
 *
 * 【定位说明（重要）】
 * - 本过滤器仅用于日志追踪/调试便利，不作为鉴权依据！
 * - 鉴权唯一真相源：下游服务 StpUtil 直查 Redis 共享会话（业务现状即如此）
 * - ⚠️ 服务端口（8081/8082）可绕过网关直连，若将来信任此 Header 做鉴权，
 *   存在身份伪造风险（FeignAuthInterceptor 也注入同名 Header）
 * - 注入 Header 前必须完成 StpUtil.isLogin() 校验（白名单路径不注入）
 *
 * 【用户信息来源（网关无状态，不查库）】
 * - userId：JWT 必含（loginId），始终注入
 * - username / roles / permissions：JWT extras，登录时由 auth 服务通过 setExtra 写入，
 *   若未写入则跳过对应 Header（优雅降级，不影响主流程）
 *
 * 【auth 服务登录时需写入 extras（示例）】
 * StpUtil.login(userId, new SaLoginModel()
 *     .setExtra("username", user.getUsername())
 *     .setExtra("roles", "READER,AUTHOR")
 *     .setExtra("permissions", "article:read,article:write"));
 *
 * 【执行顺序】
 * - SaReactorFilter 为 WebFilter（order=-100），先完成认证
 * - 本过滤器为 GlobalFilter，在路由转发前执行，天然晚于 WebFilter
 *
 * @author litisle
 */
@Component
public class UserContextHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 未登录（白名单路径）直接放行，不注入用户上下文
        if (!StpUtil.isLogin()) {
            return chain.filter(exchange);
        }

        ServerHttpRequest.Builder builder = exchange.getRequest().mutate();

        // 1. userId（JWT 必含）
        Object loginId = StpUtil.getLoginId();
        builder.header(HeaderConstant.USER_ID, String.valueOf(loginId));

        // 2. username（JWT extras，可能缺失）
        String username = toCsv(StpUtil.getExtra("username"));
        if (username != null) {
            builder.header(HeaderConstant.USERNAME, username);
        }

        // 3. roles（JWT extras，逗号分隔）
        String roles = toCsv(StpUtil.getExtra("roles"));
        if (roles != null) {
            builder.header(HeaderConstant.USER_ROLES, roles);
        }

        // 4. permissions（JWT extras，逗号分隔）
        String permissions = toCsv(StpUtil.getExtra("permissions"));
        if (permissions != null) {
            builder.header(HeaderConstant.PERMISSIONS, permissions);
        }

        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    /**
     * 将 extras 值统一转为逗号分隔字符串
     * 支持 String / Collection（List、Set 等），其他类型直接 toString
     */
    private String toCsv(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> collection) {
            return String.join(",", collection.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    @Override
    public int getOrder() {
        return -90;
    }
}