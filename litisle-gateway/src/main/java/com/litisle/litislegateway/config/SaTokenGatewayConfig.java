package com.litisle.litislegateway.config;

import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.litislegateway.filter.GlobalExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 网关过滤器配置
 *
 * 【说明】
 * 替代原 JwtAuthGlobalFilter，使用 Sa-Token 原生 SaReactorFilter 处理认证
 * 白名单路径放行，其他路径要求登录
 *
 * @author litisle
 */
@Configuration
public class SaTokenGatewayConfig {

    /**
     * 注册 Sa-Token 全局过滤器
     *
     * 【优先级】
     * SaReactorFilter 默认 order = -100，比 SaReactorFilter 之前执行
     * 符合网关认证需要在路由转发之前执行的要求
     */
    @Bean
    public SaReactorFilter getSaReactorFilter(GatewayJwtProperties properties) {
        return new SaReactorFilter()
                // 拦截所有地址
                .addInclude("/**")
                // 放行白名单（从配置文件读取）
                .addExclude(properties.getPaths().toArray(new String[0]))
                // 认证函数：所有拦截的地址都需要登录
                .setAuth(auth -> {
                    SaRouter.match("/**").check(r -> StpUtil.checkLogin());
                })
                // 异常处理：返回统一 JSON 格式
                // 由 SaReactorFilter 内部调用 SaFilterErrorStrategy，仅接收 Throwable 单参数
                .setError(e -> GlobalExceptionHandler.handleException(e));
    }
}