package com.litisle.litislecontent.constant;

import java.util.regex.Pattern;

/**
 * content 模块业务常量（板块一 [1.3]）——限流阈值见 {@link ContentLimitConstant}，此处不重复
 * <p>切片上限已收口 review 消费端（llm.max-text-chars），content 侧禁再持有切片常量（SSOT）</p>
 *
 * @author litisle
 */
public final class ContentBizConstant {

    /** 私有构造：常量类禁止实例化 */
    private ContentBizConstant() {
    }

    // ==================== 插图链路 ====================

    /**
     * file 侧插图业务类型（DictEnum name 口径）
     * <p>插图归属校验双条件之一：fileId 归属本人 && bizType=ARTICLE_IMG，
     * 防引用他人文件/违规图（失败抛 ARTICLE_IMG_REFUSED）</p>
     */
    public static final String FILE_BIZ_TYPE_ARTICLE_IMG = "ARTICLE_IMG";

    /**
     * 正文图片占位符匹配模式（{img:file_id}，编辑器插入、渲染期替换）
     * <p>占位符为纯文本节点：HTML 白名单 sanitize 必须原样保留，禁净化破坏（板块一 1.2 红线）</p>
     */
    public static final Pattern IMG_PLACEHOLDER_PATTERN =
            Pattern.compile("\\{img:([A-Za-z0-9_-]{1,64})}");

    /**
     * 渲染后的 <img> class 前缀（对齐权威在 article_image.align，渲染时拼 align 小写名为 class）
     * <p>前端按 content-img + align-* 选择器布局，改前缀须同步前端</p>
     */
    public static final String RENDERED_IMG_CLASS_PREFIX = "content-img align-";

    // ==================== content.unpublished 下架原因（ContentUnpublishedEvent.reason 字典） ====================

    /** 下架原因：1-作者删除 */
    public static final int UNPUBLISH_REASON_DELETE = 1;

    /** 下架原因：2-下架（复审驳回 2→4 / 封禁连带，板块八消费端使用） */
    public static final int UNPUBLISH_REASON_TAKEN_DOWN = 2;

    /** 下架原因：3-转私密 */
    public static final int UNPUBLISH_REASON_TO_PRIVATE = 3;
}