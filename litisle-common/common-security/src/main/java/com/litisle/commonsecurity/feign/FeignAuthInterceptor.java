package com.litisle.commonsecurity.feign;


import com.litisle.commoncore.constant.HeaderConstant;
import com.litisle.commonsecurity.context.SecurityContext;
import com.litisle.commonsecurity.context.SecurityContextHolder;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Feign 拦截器（透传用户上下文）
 * 
 * 【设计说明】
 * 1. Feign 调用其他服务时，自动透传当前用户信息（userId、roles、traceId）
 * 2. 下游服务通过 Header 解析出用户信息，写入自己的 SecurityContextHolder
 * 3. 实现"链路上用户身份的传递"
 * 
 * 【为什么需要透传？】
 * - 微服务架构中，服务间通过 Feign 调用
 * - 网关校验 JWT 后，将用户信息注入 Header
 * - 业务服务 A 调用业务服务 B 时，B 也需要知道"是谁在调用"
 * - 因此 Feign 拦截器需要将 A 的用户信息透传给 B
 * 
 * 【透传流程】
 * 客户端 → 网关(校验JWT，注入Header) → 服务A(解析Header)
 *      → 服务A通过Feign调用服务B → Feign拦截器透传Header
 *      → 服务B(解析Header，获取用户信息)
 *
 * 只要你用 Feign Client 去调用别的微服务，每一个 Feign 请求都会被它拦到。
 * （通过 @FeignClient 接口​ 发起的调用，全都会进 apply() ）
 *
 * 【使用示例】
 * // 服务A 中调用服务B
 * @Autowired
 * private UserFeignApi userFeignApi;
 * 
 * public void someMethod() {
 *     // Feign 拦截器会自动透传当前用户信息
 *     UserVO user = userFeignApi.getUserById(123L);
 * }
 * 
 * @author litisle
 */
@Slf4j
@Component
public class FeignAuthInterceptor implements RequestInterceptor {

    /**
     * 拦截 Feign 请求，注入用户上下文 Header
     * 
     * @param template Feign 请求模板
     */
    @Override
    public void apply(RequestTemplate template) {
        // 1. 获取当前线程的安全上下文
        SecurityContext context = SecurityContextHolder.getContext();

        if (context != null && context.isAuthenticated()) {
            // 2. 注入用户ID
            template.header(HeaderConstant.USER_ID, String.valueOf(context.getUserId()));

            // 3. 注入用户名
            if (context.getUsername() != null) {
                template.header("X-Username", context.getUsername());
            }

            // 4. 注入角色列表（多个角色用逗号分隔）
            Set<String> roles = context.getRoles();
            if (roles != null && !roles.isEmpty()) {
                template.header(HeaderConstant.USER_ROLES, String.join(",", roles));
            }

            // 5. 注入链路追踪ID
            if (context.getTraceId() != null) {
                template.header(HeaderConstant.TRACE_ID, context.getTraceId());
            }

            log.debug("Feign 透传用户上下文: userId={}, traceId={}", 
                    context.getUserId(), context.getTraceId());
        } else {
            // 未登录场景（如内部定时任务调用），只透传 traceId
            log.debug("Feign 调用无用户上下文（未登录场景）");
        }
    }
}