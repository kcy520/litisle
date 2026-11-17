package com.litisle.litisleai.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 改写候选版本（板块十五 15.1：多级改写建议的候选条目）
 *
 * <p>【职责边界】校验载体与响应体共用同一 DTO：SuggestionValidator#validateRewrite
 * 校验通过后组装本条目，WritingAssistService / XiaoyueSuggestionService 直接透传到响应，
 * 不做二次映射。</p>
 *
 * @author litisle
 */
@Data
public class RewriteCandidate implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 改写版本内容（TITLE 粒度受标题字数上限约束，service 侧校验） */
    private String version;

    /** 一句话改动理由（改了什么、为什么改，字数上限配置外置） */
    private String reason;

    /**
     * 空构造（JSON 反序列化用）
     */
    public RewriteCandidate() {
    }

    /**
     * 全参构造（校验器组装用）
     *
     * @param version 改写版本内容
     * @param reason  一句话改动理由
     */
    public RewriteCandidate(String version, String reason) {
        this.version = version;
        this.reason = reason;
    }
}
