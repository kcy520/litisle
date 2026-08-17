package com.litisle.commonsecurity.satoken;

import cn.dev33.satoken.listener.SaTokenListener;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sa-Token 会话事件监听器
 *
 * 【设计说明】
 * 1. 实现 SaTokenListener 接口，监听 Sa-Token 框架所有认证事件
 * 2. 与 AuditLogAspect 职责分离：
 *    - AuditLogAspect：业务操作审计（@AuditLog 注解驱动）
 *    - 本类：认证事件审计（框架事件驱动，无需注解）
 * 3. 输出统一格式审计日志，便于后续接入 MQ 或日志采集系统
 *
 * 【监听的事件类型】
 * - 登录（doLogin）        ：用户登录成功，记录 loginId、设备类型、是否记住我
 * - 登出（doLogout）       ：用户主动退出登录
 * - 被踢下线（doKickout）   ：管理员强制下线
 * - 被顶下线（doReplaced）  ：同账号在其他设备登录导致下线
 * - 被封禁（doDisable）     ：账号被管理员封禁
 * - 解封（doUntieDisable）  ：账号被封禁后恢复
 * - Token 续期（doRenewTimeout）：Token 有效期续期
 *
 * 【日志格式】
 * 与 AuditLogAspect 保持一致，使用 JSON 格式输出，包含 event 字段区分事件类型
 *
 * 【安全考虑】
 * - Token 值脱敏处理：只输出前8位和后4位，中间用 ... 替代
 * - 避免敏感信息泄露到日志中
 *
 * 【扩展方向】
 * - 短期：接入 MQ 异步写入审计数据库
 * - 中期：异常登录检测（异地登录、频繁登录失败）
 * - 长期：用户行为分析（登录时段分布、设备偏好）
 *
 * @author litisle
 */
@Slf4j
@RequiredArgsConstructor
public class SaTokenEventListener implements SaTokenListener {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;

    // ==================== 核心认证事件 ====================

    /**
     * 登录成功事件
     *
     * 【触发时机】StpUtil.login(loginId) 调用成功后
     * 【审计价值】记录谁、何时、用什么设备登录，是安全审计最重要的数据源
     */
    @Override
    public void doLogin(String loginType, Object loginId, String tokenValue, SaLoginParameter loginParameter) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("event", "LOGIN");
        logMap.put("loginId", String.valueOf(loginId));
        logMap.put("loginType", loginType);
        logMap.put("time", LocalDateTime.now().format(DT_FMT));

        if (loginParameter != null) {
            logMap.put("device", loginParameter.getDeviceType());
            logMap.put("isRemember", loginParameter.getIsLastingCookie());
            logMap.put("timeout", loginParameter.getTimeout());
        }

        logMap.put("token", maskToken(tokenValue));
        log.info("【审计日志】{}", toJson(logMap));
    }

    /**
     * 登出事件
     *
     * 【触发时机】StpUtil.logout() 调用后
     */
    @Override
    public void doLogout(String loginType, Object loginId, String tokenValue) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("event", "LOGOUT");
        logMap.put("loginId", String.valueOf(loginId));
        logMap.put("loginType", loginType);
        logMap.put("time", LocalDateTime.now().format(DT_FMT));
        logMap.put("token", maskToken(tokenValue));
        log.info("【审计日志】{}", toJson(logMap));
    }

    /**
     * 被踢下线事件
     *
     * 【触发时机】StpUtil.kickout(loginId) 调用后，被踢用户的会话被注销时触发
     * 【审计价值】记录管理员强制下线操作，用于安全审计追溯
     */
    @Override
    public void doKickout(String loginType, Object loginId, String tokenValue) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("event", "KICKOUT");
        logMap.put("loginId", String.valueOf(loginId));
        logMap.put("loginType", loginType);
        logMap.put("time", LocalDateTime.now().format(DT_FMT));
        logMap.put("token", maskToken(tokenValue));
        log.info("【审计日志】{}", toJson(logMap));
    }

    /**
     * 被顶下线事件
     *
     * 【触发时机】isConcurrent=false 时，新设备登录导致旧设备会话被注销
     */
    @Override
    public void doReplaced(String loginType, Object loginId, String tokenValue) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("event", "REPLACED");
        logMap.put("loginId", String.valueOf(loginId));
        logMap.put("loginType", loginType);
        logMap.put("time", LocalDateTime.now().format(DT_FMT));
        logMap.put("token", maskToken(tokenValue));
        log.info("【审计日志】{}", toJson(logMap));
    }

    /**
     * 账号被封禁事件
     *
     * 【触发时机】StpUtil.disable(loginId, time) 调用后
     * 【审计价值】记录封禁操作，含封禁时长，用于安全风控分析
     */
    @Override
    public void doDisable(String loginType, Object loginId, String service, int level, long disableTime) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("event", "DISABLE");
        logMap.put("loginId", String.valueOf(loginId));
        logMap.put("loginType", loginType);
        logMap.put("service", service);
        logMap.put("level", level);
        logMap.put("disableTime", disableTime);
        logMap.put("time", LocalDateTime.now().format(DT_FMT));
        log.info("【审计日志】{}", toJson(logMap));
    }

    /**
     * 账号解封事件
     *
     * 【触发时机】StpUtil.untieDisable(loginId) 调用后
     */
    @Override
    public void doUntieDisable(String loginType, Object loginId, String service) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("event", "UNTIE_DISABLE");
        logMap.put("loginId", String.valueOf(loginId));
        logMap.put("loginType", loginType);
        logMap.put("service", service);
        logMap.put("time", LocalDateTime.now().format(DT_FMT));
        log.info("【审计日志】{}", toJson(logMap));
    }

    // ==================== 二级认证事件 ====================

    /**
     * 打开二级认证事件
     */
    @Override
    public void doOpenSafe(String loginType, String tokenValue, String service, long safeTime) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("event", "OPEN_SAFE");
        logMap.put("loginType", loginType);
        logMap.put("service", service);
        logMap.put("safeTime", safeTime);
        logMap.put("time", LocalDateTime.now().format(DT_FMT));
        logMap.put("token", maskToken(tokenValue));
        log.info("【审计日志】{}", toJson(logMap));
    }

    /**
     * 关闭二级认证事件
     */
    @Override
    public void doCloseSafe(String loginType, String tokenValue, String service) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("event", "CLOSE_SAFE");
        logMap.put("loginType", loginType);
        logMap.put("service", service);
        logMap.put("time", LocalDateTime.now().format(DT_FMT));
        logMap.put("token", maskToken(tokenValue));
        log.info("【审计日志】{}", toJson(logMap));
    }

    // ==================== Session 事件 ====================

    /**
     * 创建 Session 事件
     */
    @Override
    public void doCreateSession(String id) {
        log.debug("Sa-Token Session 创建: id={}", id);
    }

    /**
     * 注销 Session 事件
     */
    @Override
    public void doLogoutSession(String id) {
        log.debug("Sa-Token Session 注销: id={}", id);
    }

    // ==================== Token 续期事件 ====================

    /**
     * Token 续期事件
     *
     * 【触发时机】Token timeout 到期前自动续期（非 active-timeout）
     */
    @Override
    public void doRenewTimeout(String loginType, Object loginId, String tokenValue, long timeout) {
        Map<String, Object> logMap = new LinkedHashMap<>();
        logMap.put("event", "RENEW_TIMEOUT");
        logMap.put("loginId", String.valueOf(loginId));
        logMap.put("loginType", loginType);
        logMap.put("newTimeout", timeout);
        logMap.put("time", LocalDateTime.now().format(DT_FMT));
        logMap.put("token", maskToken(tokenValue));
        log.info("【审计日志】{}", toJson(logMap));
    }

    // ==================== 工具方法 ====================

    /**
     * Token 脱敏处理
     *
     * 只保留前8位和后4位，中间用 ... 替代。
     * 短 Token（≤12位）直接返回 "***" 避免泄露。
     */
    private String maskToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isEmpty()) {
            return "N/A";
        }
        if (tokenValue.length() <= 12) {
            return "***";
        }
        return tokenValue.substring(0, 8) + "..." + tokenValue.substring(tokenValue.length() - 4);
    }

    /**
     * Map 转 JSON 字符串
     *
     * 序列化失败时降级为 toString()，避免因序列化异常中断业务流程
     */
    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return map.toString();
        }
    }
}