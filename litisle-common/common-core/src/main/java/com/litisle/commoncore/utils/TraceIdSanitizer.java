package com.litisle.commoncore.utils;

import java.util.UUID;

/**
 * traceId 日志卫生工具（板块七 7-2.1「落日志前剥离 \r\n」收口，契约库 SSOT）。
 *
 * 它是 traceId 进入 MDC / Header / 日志之前的“消毒器 + 兜底生成器”，确保链路 ID 既安全又不断链。
 * 如果恶意请求在 X-Trace-Id 中携带 \r\n（CRLF 注入），直接写入日志后，会在日志中伪造出一整行假日志，污染审计、干扰日志采集。
 *
 * <p>【为什么需要】traceId 是少数「外部可控且会进日志」的字段：
 * 网关复用上游 X-Trace-Id Header、MQ 消费侧恢复消息 Header 中的 traceId——
 * 若恶意/脏数据携带 \r\n（CRLF 注入），写入 MDC 后会在日志 pattern %X{traceId}
 * 处伪造出完整的假日志行（伪造审计痕迹、污染日志采集解析），即日志注入攻击面。</p>
 *
 * <p>【使用场景（全链路三个写入点都必须先过本类，禁绕行）】</p>
 * <ul>
 *   <li>网关 TraceIdFilter：复用上游 Header 后、写入 MDC/下游 Header 前；</li>
 *   <li>common-security TraceIdFilter（Servlet 服务侧）：读 Header 后、写 MDC 前；</li>
 *   <li>common-mq MqTraceSupport.restore：读消息 Header 后、写 MDC 前。</li>
 * </ul>
 *
 * <p>【链路位置】客户端 → 网关(生成/复用+本类净化) → 服务A(读Header+本类净化写MDC)
 * → Feign(读MDC透传) → MQ(读MDC写消息头) → 消费端(读消息头+本类净化写MDC) → record 落库。</p>
 *
 * <p>【与 provider_trace_id 的关系】本类仅服务内部链路 traceId；
 * 厂商凭证 provider_trace_id 由模型厂商返回，分列落库（moderation_record 两列独立），
 * 互不复用、互不污染。</p>
 *
 * @author litisle
 */
public final class TraceIdSanitizer {

    /** traceId 净化后长度上限（业务约定 32 位无连字符 UUID，64 兑底防极端脏 Header 撑爆日志单行） */
    private static final int MAX_LENGTH = 64;

    private TraceIdSanitizer() {
    }

    /**
     * 净化 traceId：剥离 \r\n 及全部 C0 控制字符（\u0000-\u001F）与 DEL(\u007F)，
     * 防止 CRLF/控制字符变体注入伪造日志行。
     * 作用：净化 traceId，防日志注入
     *
     * <p>调用方：所有 traceId 写入 MDC / Header / 日志前的唯一闸口。
     * 净化后为空串时返回空串（由调用方决定是否本地生成，不走本方法兜底）。</p>
     *
     * @param traceId 原始 traceId（来源：上游 Header / MQ 消息头 / 事件体字段）
     * @return 净化后的 traceId（null 安全：null 入 null 出）
     */
    public static String sanitize(String traceId) {
        // 步骤1：null 直接返回 null，不抛异常（净化属尽力而为的卫生动作，非校验动作）
        if (traceId == null) {
            return null;
        }
        // 步骤2：逐字符过滤——保留 ≥ 空格(0x20) 且非 DEL(0x7F) 的字符，
        //        即剥离 \r\n 与全部 C0 控制字符变体（含垂直制表符等日志采集器解析歧义源）
        StringBuilder cleaned = new StringBuilder(traceId.length());
        for (int i = 0; i < traceId.length(); i++) {
            char c = traceId.charAt(i);
            if (c >= 0x20 && c != 0x7F) {
                cleaned.append(c);
            }
        }
        // 步骤3：长度硬截断（MAX_LENGTH 上限，防极端脏 Header 撑爆日志单行）
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned.toString();
    }

    /**
     * 净化 + 空值兜底生成：净化后为 null/空白时本地生成 UUID（32 位无连字符，
     * 与网关 TraceIdFilter 本地生成策略同口径），保证链路任何一段都不缺 traceId。
     *
     * <p>调用方：MQ 消费侧 MqTraceSupport（消息头缺失时兜底生成，
     * 让补偿重投/手动重放的日志也能归组）。</p>
     *
     * @param traceId 原始 traceId（可为 null/空白）
     * @return 净化后的 traceId；不可用时返回新生成的 32 位无连字符 UUID
     */
    public static String sanitizeOrGenerate(String traceId) {
        // 步骤1：先过净化闸口（剥离 \r\n 等控制字符）
        String sanitized = sanitize(traceId);
        // 步骤2：净化后为 null/空白时本地生成 32 位无连字符 UUID——
        //        网关与各服务 TraceIdFilter 的生成口径统一收敛到本方法（SSOT），禁各自演化
        if (sanitized == null || sanitized.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "");
        }
        return sanitized;
    }
}