package com.litisle.commonsecurity.xss;

e:\NewJavaPro\LitIsle\litisle-common\common-security\src\main\java\com\litisle\commonsecurity\xss\XssHttpServletRequestWrapper.java
package com.litisle.commonsecurity.xss;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * XSS 请求包装器
 *
 * 【设计说明】
 * 1. 包装 HttpServletRequest，重写获取参数方法，对值进行 HTML 清洗
 * 2. 配合 XssFilter 使用，在 Filter 中替换原始请求
 * 3. 覆盖范围：getParameter / getParameterValues / getParameterMap
 *
 * 【HttpServletRequestWrapper 原理】
 * - 装饰器模式：包装原始 request，增强或修改行为
 * - 重写需要修改的方法，其他方法委托给原始 request
 * - Servlet 容器后续拿到的就是这个包装后的 request
 *
 * 【XSS 防护原理】
 * - XSS（跨站脚本攻击）：攻击者注入 <script> 等标签
 * - 防护方式：对用户输入的 HTML 标签进行转义或清除
 * - 本实现采用"清除"策略：直接移除 HTML 标签，保留文本内容
 *
 * 【为什么不用 OWASP ESAPI 或 hutool】
 * - 减少依赖：本实现仅用 JDK + Servlet API
 * - 简单场景足够：清除标签即可防 XSS，无需复杂转义
 * - 可扩展：如需更严格防护，可替换 cleanHtmlTag 实现
 *
 * 【使用示例】
 * // 在 XssFilter 中
 * HttpServletRequest wrappedRequest = new XssHttpServletRequestWrapper(request);
 * chain.doFilter(wrappedRequest, response);
 *
 * @author litisle
 */
public class XssHttpServletRequestWrapper extends HttpServletRequestWrapper {

    /**
     * 构造方法
     *
     * @param request 原始请求
     */
    public XssHttpServletRequestWrapper(HttpServletRequest request) {
        super(request);
    }

    /**
     * 获取单个参数（已清洗）
     */
    @Override
    public String getParameter(String name) {
        String value = super.getParameter(name);
        return cleanHtmlTag(value);
    }

    /**
     * 获取参数数组（已清洗）
     */
    @Override
    public String[] getParameterValues(String name) {
        String[] values = super.getParameterValues(name);
        if (values == null) {
            return null;
        }
        String[] cleaned = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            cleaned[i] = cleanHtmlTag(values[i]);
        }
        return cleaned;
    }

    /**
     * HTML 标签清洗核心方法
     *
     * 【清洗策略】
     * 1. 移除 <script> 标签及其内容（防脚本注入）
     * 2. 移除所有 HTML 标签（防标签注入）
     * 3. 转义特殊字符（防属性注入）
     *
     * 【示例】
     * - <script>alert(1)</script> → alert(1)
     * - <img src=x onerror=alert(1)> → （空）
     * - <a href="javascript:..."> → （空）
     *
     * @param value 原始值
     * @return 清洗后的值
     */
    public static String cleanHtmlTag(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        // 1. 移除 <script> 标签及内容（大小写不敏感）
        String cleaned = value.replaceAll("(?i)<script.*?>.*?</script>", "");
        // 2. 移除所有 HTML 标签
        cleaned = cleaned.replaceAll("<[^>]+>", "");
        // 3. 转义特殊字符，防止残留的标签符被解析
        cleaned = cleaned.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
        return cleaned;
    }
}