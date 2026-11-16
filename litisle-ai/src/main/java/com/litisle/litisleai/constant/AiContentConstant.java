package com.litisle.litisleai.constant;

/**
 * AI 侧内容口径常量（文章状态 / 可见性的唯一字面量来源）
 *
 * <p>【职责边界】content 模块的状态与可见性枚举只存在于 content 实现内，跨服务契约
 * ContentDTO 只透出 Integer code；AI 侧的摄入门控（RagIngestService）与引用可见性回查
 * （RagSearchService）共用本类常量，禁止各自再写数字字面量。</p>
 *
 * <p>【维护口径】取值必须与 content 模块枚举 code 保持一致，变更需随契约评审同步；
 * 业务类型 bizType 复用 litisle-api 的 {@code ModerationConstant.BIZ_TYPE_ARTICLE}。</p>
 *
 * @author litisle
 */
public final class AiContentConstant {

    private AiContentConstant() {
    }

    /** 文章状态：2-已发布（摄入与引用回查的唯一通过值） */
    public static final int STATUS_PUBLISHED = 2;

    /** 可见性：1-公开（未公开内容不出站，与 D2/V3 出线红线同口径） */
    public static final int VISIBILITY_PUBLIC = 1;
}
