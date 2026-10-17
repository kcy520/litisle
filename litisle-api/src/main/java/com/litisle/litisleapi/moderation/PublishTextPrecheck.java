package com.litisle.litisleapi.moderation;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.constant.ModerationConstant;

/**
 * 发布前置校验（板块四 4-1.1 / Review-Plan §4.2.1 ①′）——口径实现（契约库 SSOT，发布入口统一复用）。
 *
 * 判断发布前是否有真实可见的文本
 *
 * 【落位】文章/评论发布（litisle-content）、私信发送（litisle-social）、用户名注册（auth）
 * 各发布入口内、同步 DFA 闸门（DfaFacade.check）之前调用。
 *
 * 【校验口径】
 * 1. 标题：trim 后非空，且长度 ≤ {@link ModerationConstant#TITLE_MAX_LENGTH}
 *    （对齐 article.title varchar(100)）；
 * 2. 正文：去 HTML 标签后可见文本非空（防 {@code <p></p>} 空壳绕过）；标题与正文不允许同时为空；
 * 3. 时序铁律：空内容校验必须在 content_hash 计算与送审之前——
 *    空内容 content_hash 恒定，全站共享同一 hash，一条 PASS 缓存即污染全部空内容免审。
 *
 * 【边界说明】「正文为空且含图→文本链路 trivially PASS」为图文联合链路（4-3.4）职责，
 * 本校验器只做纯文本口径判定，图文分支是否调用由发布入口自行编排。
 *
 * @author litisle
 */
public final class PublishTextPrecheck {

    private PublishTextPrecheck() {
    }

    /**
     * 发布前置校验（4-1.1）：标题 trim 非空 + 长度≤100 + 正文去 HTML 标签可见文本非空。
     * 必须在 content_hash 计算与送审之前调用（空内容 hash 恒定，防 PASS 缓存污染全站空内容免审）。
     * 无需
     *
     * @param title   标题（文章/评论场景必传；私信/用户名场景传 null 仅校验 content）
     * @param content 正文/私信内容/用户名
     * @throws BizException CONTENT_EMPTY(30005)-标题/内容为空；TITLE_TOO_LONG(30006)-标题超长
     */
    public static void check(String title, String content) {
        if (title != null) {
            // 步骤1：标题 trim 非空（防纯空格绕过）
            String trimmedTitle = title.trim();
            if (trimmedTitle.isEmpty()) {
                throw new BizException(ErrorCode.CONTENT_EMPTY);
            }
            // 步骤2：标题长度上限（对齐 article.title varchar(100)）
            if (trimmedTitle.length() > ModerationConstant.TITLE_MAX_LENGTH) {
                throw new BizException(ErrorCode.TITLE_TOO_LONG);
            }
        }
        // 步骤3：正文去 HTML 标签后可见文本非空（防 <p></p> 空壳绕过）
        if (!hasVisibleText(content)) {
            throw new BizException(ErrorCode.CONTENT_EMPTY);
        }
    }

    /**
     * 判断文本去 HTML 标签后是否含可见字符。
     * 4-3.4 图文分支复用：正文为空且含图→文本链路 trivially PASS，由发布入口用本方法判定。
     *
     * @param text 原始文本（可为 null）
     * @return true-存在可见文本
     */
    public static boolean hasVisibleText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return !stripHtmlTags(text).isEmpty();
    }

    /**
     * 剥离 HTML 标签与空白实体，仅保留可见文本（防 <p></p> 空壳与 &nbsp; 类实体绕过）。
     *
     * @param text 原始文本（可为 null）
     * @return 可见文本（trim 后，无可见字符时返回空串）
     */
    public static String stripHtmlTags(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 步骤1：整块剔除 script/style 内容（防脚本/样式正文伪装成可见文本）
        String cleaned = text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        // 步骤2：剔除剩余标签与 &nbsp; 空白实体
        cleaned = cleaned.replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ");
        return cleaned.trim();
    }
}
