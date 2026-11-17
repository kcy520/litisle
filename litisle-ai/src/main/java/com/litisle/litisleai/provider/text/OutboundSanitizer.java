package com.litisle.litisleai.provider.text;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 出站脱敏过滤器（P1 定档 Q63「边界修订」路线：未公开稿件外发前统一脱敏）
 *
 * <p>【职责边界】本类只做文本脱敏（手机号/邮箱/身份证/外部链接四类正则替换），
 * 不判断内容是否公开、不做闸门拦截——拦截语义由调用方与 DFA 闸门承担；
 * 脱敏在 provider 出站前统一执行（DesensitizingChatModel 装饰器接线），
 * 覆盖机审通道与 Agent 通道所有出站请求（含工具结果回喂文本）。</p>
 *
 * <p>【替换口径】命中内容统一替换为中文占位符（不保留部分明文，
 * 最小必要原则：模型只需知道「这里有个手机号」即可完成任务，无需真实值）。</p>
 *
 * <p>【日志纪律】只记录替换次数与文本长度，禁止打印命中内容原文。</p>
 *
 * <p>【通俗说明】出站内容的"保密检查员"：凡是发往外部模型的文字（prompt、工具回喂的
 * 稿件片段等），先过它这一关——用四条正则把手机号、邮箱、身份证号、外部链接统统
 * 换成"[手机号]""[邮箱]"这样的占位符。模型只需要知道"这里有个手机号"就能干活，
 * 不需要知道真实号码是什么。它只负责"擦掉敏感信息"，不管内容该不该外发
 * （拦不拦得住由调用方和 DFA 闸门决定）；日志里也只记替换了几处，绝不打印原文。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
public class OutboundSanitizer {

    /** 手机号（13x~19x 共 11 位，前后非数字防误切长数字串） */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 邮箱（通用格式） */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 身份证号（18 位，末位可为 X/x；先于手机号执行——18 位串不会被手机号规则误命中） */
    private static final Pattern ID_CARD_PATTERN =
            Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");

    /** 外部链接（http/https 开头的 URL） */
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://\\S+");

    /** 手机号占位符 */
    private static final String PHONE_PLACEHOLDER = "[手机号]";

    /** 邮箱占位符 */
    private static final String EMAIL_PLACEHOLDER = "[邮箱]";

    /** 身份证占位符 */
    private static final String ID_CARD_PLACEHOLDER = "[身份证号]";

    /** 外部链接占位符 */
    private static final String URL_PLACEHOLDER = "[外部链接]";

    /**
     * 对文本执行四类规则脱敏：返回替换后的文本；入参为 null/空串原样返回
     *
     * @param text 待脱敏文本（prompt/工具结果回喂等出站内容）
     * @return 脱敏后的文本（无命中时与入参相同）
     *
     * <p>【通俗说明】四条规则按固定顺序过一遍：先换链接（防 URL 里的数字被手机号规则
     * 拦腰截走），再换身份证（18 位里包含 11 位手机号样式，先长后短防误伤），然后
     * 邮箱、最后手机号。空文本直接原样返回；有没有命中通过"替换前后文本是否变了"
     * 粗略判断，命中就记一条不露内容的观测日志。</p>
     */
    public String sanitize(String text) {
        // 空文本无脱敏必要，原样返回
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        int hits = 0;
        // 顺序：身份证先于手机号（18 位串含 11 位子串，先长后短防误伤）；链接最先占位避免 URL 内数字被二次替换
        result = replaceAll(result, URL_PATTERN, URL_PLACEHOLDER);
        result = replaceAll(result, ID_CARD_PATTERN, ID_CARD_PLACEHOLDER);
        result = replaceAll(result, EMAIL_PATTERN, EMAIL_PLACEHOLDER);
        result = replaceAll(result, PHONE_PATTERN, PHONE_PLACEHOLDER);
        // 命中数按「脱敏前后长度差异不为 0 即至少命中一次」粗记，精确计数仅日志观测用
        hits = text.equals(result) ? 0 : 1;
        if (hits > 0) {
            // 日志纪律：只记字符数变化，不打印命中内容
            log.debug("出站脱敏命中：文本 {} 字符，替换后 {} 字符", text.length(), result.length());
        }
        return result;
    }

    /**
     * 单规则全文替换（每处命中替换为占位符）
     *
     * @param input       待处理文本
     * @param pattern     规则正则
     * @param placeholder 替换占位符
     * @return 替换后的文本
     *
     * <p>【通俗说明】单条规则的执行器：先扫一眼文本里有没有命中，没有就原样返回
     * （省一次整体替换的开销）；命中则复位匹配器后全文替换——占位符套一层
     * quoteReplacement，防止占位符里万一出现 $、\ 这类正则特殊字符时被误解析。</p>
     */
    private String replaceAll(String input, Pattern pattern, String placeholder) {
        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }
        // reset 后整体替换（find 已消费匹配位置，需先复位）
        matcher.reset();
        return matcher.replaceAll(Matcher.quoteReplacement(placeholder));
    }
}
