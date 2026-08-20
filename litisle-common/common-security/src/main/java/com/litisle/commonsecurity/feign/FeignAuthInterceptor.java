package com.litisle.commonsecurity.feign;


import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.constant.HeaderConstant;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.List;

/**
 * Feign 拦截器（透传用户上下文）
 * 
 * 【设计说明】
 * 1. Feign 调用其他服务时，自动透传当前用户信息（userId、roles、traceId）
 * 2. 下游服务通过 Header 解析出用户信息，写入自己的 Sa-Token 上下文
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
public class FeignAuthInterceptor implements RequestInterceptor {

    /**
     * 拦截 Feign 请求，注入用户上下文 Header
     * 
     * @param template Feign 请求模板
     */
    @Override
    public void apply(RequestTemplate template) {
        // 1. 先透传链路追踪ID（无论是否登录，保证链路不中断）
        //    从 MDC 获取（由 TraceIdFilter 写入），与用户上下文解耦
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            template.header(HeaderConstant.TRACE_ID, traceId);
        }

        // 2. 检查是否登录（Sa-Token 方式，替代原 SecurityContextHolder.getContext()）
        if (!StpUtil.isLogin()) {
            // 未登录场景（如内部定时任务调用），仅透传 traceId
            log.debug("Feign 调用无用户上下文（未登录场景），仅透传 traceId={}", traceId);
            return;
        }

        // 3. 注入用户ID（Sa-Token 的 loginId 即业务 userId）
        Object loginId = StpUtil.getLoginId();
        template.header(HeaderConstant.USER_ID, String.valueOf(loginId));

        // 4. 注入用户名（从 Sa-Token Session 获取，登录时业务侧 session.set("username", xxx)）
        SaSession session = StpUtil.getSession();
        String username = (String) session.get("username");
        if (username != null) {
            template.header(HeaderConstant.USERNAME, username);
        }

        // 5. 注入角色列表（Sa-Token 通过 StpInterface.getRoleList() 提供）
        List<String> roles = StpUtil.getRoleList();
        if (roles != null && !roles.isEmpty()) {
            template.header(HeaderConstant.USER_ROLES, String.join(",", roles));
        }

        // ========== 6. 权限（Sa-Token 通过 StpInterface.getPermissionList() 提供） ==========
        List<String> perms = StpUtil.getPermissionList();
        if (perms != null && !perms.isEmpty()) {
            template.header(HeaderConstant.PERMISSIONS, String.join(",", perms));
        }

        // 7. 注入 Sa-Token 的 Token 值（下游服务可通过它直接查询会话，无需重复鉴权）
        String tokenValue = StpUtil.getTokenValue();
        if (tokenValue != null) {
            template.header(StpUtil.getTokenName(), tokenValue);
        }

        log.debug("Feign 透传用户上下文: userId={}, traceId={}", loginId, traceId);
    }
}