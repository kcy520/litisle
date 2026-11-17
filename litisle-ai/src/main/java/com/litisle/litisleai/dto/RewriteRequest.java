package com.litisle.litisleai.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 多级改写建议请求（板块十五 15.1：POST /ai/xiaoyue/rewrite）
 *
 * <p>【粒度口径】targetType 仅支持 TITLE/PARAGRAPH/SENTENCE/WORD（白名单校验，service 侧）；
 * TITLE 粒度承载板块十二 4.7「标题/开头多版建议」的统一承载。</p>
 *
 * <p>【原文来源】二选一：articleId（本人文章——取稿 + 归属校验，非本人所有明确拒绝，
 * AI-V6 ⑤/⑦ 口径）优先于 originalText；无 articleId 时 originalText 必传。</p>
 *
 * <p>【铁律】建议态不自动写入（D4）；恒带 AI 标识（D3/D13）；展示前过 DFA 闸门
 * （调用方执行）；改写保持作者原意（prompt 硬约束）；仅登录用户可用（写作辅助不开匿名）。</p>
 *
 * @author litisle
 */
@Data
public class RewriteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求幂等键（业务侧生成，重试复用同一键；建议态缓存回查键，必传） */
    private String requestId;

    /** 改写目标粒度（TITLE/PARAGRAPH/SENTENCE/WORD，白名单校验，必传） */
    private String targetType;

    /** 本人文章 ID（可空；传入时取稿 + 归属校验并回填 originalText，TITLE 粒度取标题、其余粒度取正文） */
    private Long articleId;

    /** 改写原文（articleId 为空时必传；创作页选中文本直接传入） */
    private String originalText;

    /** 作者风格偏好（可空：正式/口语/简洁；缺省由模型按原文风格自适配） */
    private String stylePreference;

    /** 候选版本数（可空 1–3；缺省取配置上限，越界 clamp 到 1~上限） */
    private Integer candidateCount;
}
