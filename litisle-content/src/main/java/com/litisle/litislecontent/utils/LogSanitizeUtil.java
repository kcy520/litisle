package com.litisle.litislecontent.utils;


/**
 * 日志注入净化器（板块一 [1.2/1.3] Controller 收尾项）
 * <p>UGC 自由文本（标题/驳回理由/下架原因透传等）落日志前剥 \r\n 与控制字符，
 * 防攻击者借伪造换行注入假日志行抹去溯源痕迹（对齐 review §七.1 UGC 落日志口径）</p>
 * <p>使用场景：作者侧操作日志、审核理由透传、下架原因记录等一切含用户可控文本的 log 输出</p>
 *
 * @author litisle
 */
public final class LogSanitizeUtil {

    /** 私有构造：工具类禁止实例化 */
    private LogSanitizeUtil() {
    }

    /** 控制字符匹配（\r\n 换行 + \t 制表 + 其余 ASCII 控制符，统一折叠为空格） */
    private static final String CONTROL_CHARS = "[\\x00-\\x1f\\x7f]";

    /** 单条日志文本长度上限（超长截断防日志爆炸刷盘） */
    private static final int MAX_LOG_LENGTH = 200;

    /**
     * 净化待落日志的自由文本：控制字符（含 \r\n）折叠为空格 + 超长截断
     *
     * @param text 用户可控文本（可 null）
     * @return 单行安全文本（null 返回 "null" 原样，不影响日志语义）
     */
    public static String sanitizeForLog(String text) {
        if (text == null) {
            return "null";
        }
        // 步骤1：\r\n 等控制字符折叠为空格（换行注入即失效，无法伪造新日志行）
        String sanitized = text.replaceAll(CONTROL_CHARS, " ");
        // 步骤2：超长截断（防刷日志攻击撑爆存储）
        return sanitized.length() > MAX_LOG_LENGTH
                ? sanitized.substring(0, MAX_LOG_LENGTH) + "...(truncated)"
                : sanitized;
    }
}