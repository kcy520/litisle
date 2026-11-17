package com.litisle.litisleai.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 文章草稿生成请求（板块十五 15.2：POST /ai/xiaoyue/draft）
 *
 * <p>【采用路径（关键边界）】AI 不落库——作者编辑后由前端带入 content 正常创建流程
 * （走完整 DFA 同步闸门 + 机审主链路，AI 草稿不绕过送审）；AI 会话侧副本按 90 天清理。</p>
 *
 * <p>【铁律】草稿仅参考、作者可关闭；出站脱敏由装饰链自动覆盖；prompt 版本由
 * model_version 承载（复用键与评测回归可追溯）；仅登录用户可用（写作辅助不开匿名）。</p>
 *
 * @author litisle
 */
@Data
public class DraftRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求幂等键（业务侧生成，重试复用同一键；建议态缓存回查键，必传） */
    private String requestId;

    /** 草稿主题（必传，service 侧校验） */
    private String theme;

    /** 大纲/要点（可空；传入时作为草稿结构与内容的约束输入） */
    private String outline;

    /** 字数范围下限（可空，字） */
    private Integer wordCountMin;

    /** 字数范围上限（可空，字） */
    private Integer wordCountMax;

    /**
     * 组装字数范围要求文本（prompt 组装用）
     *
     * @return 字数范围描述（两端为「min~max 字」；只有下限「不少于 N 字」；只有上限
     *         「不超过 N 字」；均空「不限」）
     */
    public String wordRangeText() {
        if (wordCountMin == null && wordCountMax == null) {
            return "不限";
        }
        if (wordCountMin != null && wordCountMax != null) {
            return wordCountMin + "~" + wordCountMax + " 字";
        }
        return wordCountMin != null ? "不少于 " + wordCountMin + " 字" : "不超过 " + wordCountMax + " 字";
    }
}
