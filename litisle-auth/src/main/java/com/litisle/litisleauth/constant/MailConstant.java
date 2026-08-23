package com.litisle.litisleauth.constant;

/**
 * auth 模块邮件常量
 *
 * 【设计说明】
 * 1. 集中管理邮件主题、正文模板、落款、免责声明等文案
 * 2. 文案属于运营内容，独立成类便于统一维护与后续多语言扩展
 */
public final class MailConstant {

    private MailConstant() {
    }

    /** 邮件主题 */
    public static final String SUBJECT = "LitIsle 邮箱验证码";

    /** 邮件内容模板：%s=验证码，%d=有效分钟数 */
    public static final String CONTENT_TEMPLATE = "您的验证码是：<b>%s</b>，%d 分钟内有效，请勿泄露给他人。";

    /** 邮件落款（签名） */
    public static final String SIGNATURE = "<br/><br/>—— LitIsle 团队";

    /** 邮件免责声明（防钓鱼提示） */
    public static final String DISCLAIMER = "<p style=\"color:#999;font-size:12px;\">此邮件由系统自动发送，请勿直接回复。若您未发起该操作，请忽略此邮件。</p>";
}