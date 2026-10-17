package com.litisle.litislecontent.utils;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * UGC 富文本净化器（板块一 [1.2] sanitize 三层铁律核心，存储型 XSS 入库前收口）
 *
 * 职责分离铁律：审核只判定不净化，净化与判定分离；净化必须在入库前收口
 * （review 后台内容预览直吐库中 HTML，禁仅出库净化——存储型 XSS 红线，对齐 Content-Architecture §七.1）
 *
 * 三层口径：
 * ① 标题/摘要 stripTags 剥标签纯文本落库（防 img onerror 藏标题）
 * ② 正文 sanitize HTML 白名单净化——丢弃全部属性（on/style/src 一并消灭）、
 *    非白名单标签剥壳留内容、script/style 块连内容整删；
 *    {img:file_id} 为纯文本节点天然不受影响
 * ③ 回显输出编码双保险由前端/网关承担
 *
 * 实现说明：一期最小正则白名单（项目无 jsoup/owasp 依赖，不为此单引重依赖）；
 * 二期若富文本规则复杂化可平滑替换为 OWASP java-html-sanitizer，方法签名不变
*/
public final class ArticleHtmlSanitizer {

    /** 私有构造：工具类禁止实例化 */
    private ArticleHtmlSanitizer() {
    }

    /**
     * script/style 整块删除（含内部内容——JS/CSS 注入源头，禁止剥壳留内容）
     */
    private static final Pattern SCRIPT_STYLE_BLOCK =
            Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");

    /**
     * HTML 注释删除（条件注释可藏 IE 专属攻击载荷）
     */
    private static final Pattern HTML_COMMENT = Pattern.compile("(?is)<!--.*?-->");

    /**
     * 标签匹配：必须完整匹配到 '>'，防止误吞正文内容（如 &lt;3、&lt;=10 等）
     * 修复前 &gt;? 为可选导致贪婪匹配吞掉大量正常文本
     */
    private static final Pattern TAG = Pattern.compile("(?is)<\\s*(/?)\\s*([a-zA-Z0-9]+)[^>]*>");

    /**
     * 标签白名单（纯排版语义；不含 img/a/form/iframe/input——图片走 {img:file_id} 占位符，外链一期不放行）
     */
    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "blockquote", "pre", "code",
            "strong", "b", "em", "i", "u", "s", "span", "div"
    );

    /**
     * 剥除全部 HTML 标签，输出纯文本（标题/摘要落库口径——sanitize 三层之①）
     * <p>标签之间的空白折叠为单空格，防剥壳后单词粘连</p>
     *
     * @param raw 原始输入（可 null）
     * @return 纯文本（trim 后；null 输入返回 null）
     */
    public static String stripTags(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        // 步骤1：script/style 块连内容整删（防 JS 体残留为文本）
        String stripped = SCRIPT_STYLE_BLOCK.matcher(raw).replaceAll(" ");
        // 步骤2：逐标签剥壳（仅删标签 token，保留标签间文本）
        stripped = TAG.matcher(stripped).replaceAll(" ");
        // 步骤3：空白折叠 + trim
        return stripped.replaceAll("\\s+", " ").trim();
    }

    /**
     * HTML 白名单净化（正文落库口径——sanitize 三层之②）
     *
     * 对用户输入的富文本 HTML 做“白名单净化”，只保留安全的排版标签，
     * 把所有危险标签/属性全部干掉，输出一份可以安全存入数据库的 HTML。
     *
     * <p>规则：①script/style 块与注释整删；②白名单标签重建为无属性纯净标签；
     * ③非白名单标签仅删标签 token（剥壳留内容，防正文误删）；
     * ④{img:file_id} 占位符为纯文本节点，天然保留（渲染链路依赖，禁破坏）</p>
     *
     * @param rawHtml 原始正文 HTML（可 null）
     * @return 净化后 HTML（null 输入返回 null）
     */
    public static String sanitize(String rawHtml) {
        if (rawHtml == null || rawHtml.isEmpty()) {
            return rawHtml;
        }
        // 步骤1：script/style 整块 + 注释删除
        String cleaned = SCRIPT_STYLE_BLOCK.matcher(rawHtml).replaceAll("");
        cleaned = HTML_COMMENT.matcher(cleaned).replaceAll("");

        // 步骤2：逐标签白名单重建（丢弃全部属性）
        Matcher matcher = TAG.matcher(cleaned);
        StringBuilder sb = new StringBuilder(cleaned.length());
        while (matcher.find()) {
            String tagName = matcher.group(2).toLowerCase();
            String replacement = ALLOWED_TAGS.contains(tagName)
                    ? "<" + matcher.group(1) + tagName + ">"
                    : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}