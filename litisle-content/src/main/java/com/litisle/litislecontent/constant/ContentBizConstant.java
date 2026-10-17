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

    // ==================== 关注关系回查缓存（[4.1] visibility=3 仅关注者判定） ====================

    /**
     * 关注关系缓存键前缀（拼接 ownerId:viewerId，值 1-关注 0-非关注；负缓存同样生效防穿透）
     * <p>取关后最长 TTL 窗口内仍判关注者属可接受弱一致性（follow.changed 事件联动留二期）</p>
     */
    public static final String FOLLOW_RELATION_CACHE_KEY_PREFIX = "content:follow:relation:";

    /** 关注关系缓存 TTL 基准秒数（实际写入 ±10% 抖动，防同批键同刻失效雪崩） */
    public static final long FOLLOW_RELATION_CACHE_TTL_SECONDS = 60L;

    // ==================== 密码访问防爆破与验证凭证（[4.2]） ====================

    /**
     * 密码尝试防爆破 identifier 前缀（拼接 operatorId）
     * <p>与 auth 登录失败计数共用 LoginRetryLimiter（key=login:fail:{identifier}），
     * 前缀隔离命名空间，防与纯数字用户名互相污染计数</p>
     */
    public static final String PASSWORD_RETRY_IDENTIFIER_PREFIX = "article-pwd:";

    /** 密码最大连续失败次数（达到后锁定，对齐 auth 登录防爆破口径） */
    public static final int PASSWORD_RETRY_MAX_ATTEMPTS = 5;

    /** 密码连续失败锁定时长（秒；60s 窗口对齐 §八限流矩阵） */
    public static final long PASSWORD_LOCK_SECONDS = 60L;

    /** 密码验证通过标记键前缀（拼接 operatorId:articleId，值固定 1） */
    public static final String PWD_PASS_MARK_KEY_PREFIX = "content:pwd-pass:";

    /** 密码验证通过标记 TTL（秒；窗口内 getDetail PASSWORD 分支放行正文，过期重验） */
    public static final long PWD_PASS_MARK_TTL_SECONDS = 600L;

    // ==================== 文章双链路审核聚合暂存（板块三 3-5） ====================

    /**
     * 文章双链路聚合暂存键前缀（拼接 articleId，Redis Hash：field text/img → verdict 终局码）
     * <p>文本 result（scene=ARTICLE）与配图复审 result（scene=ARTICLE_IMG_RE_REVIEW）并发到达，
     * 先到一路写槽等待另一路，两路齐备后聚合判定再 CAS article（对齐 §4.5.2 口径：
     * 放行=文本 PASS 且（无配图 或 图片复审 PASS）；任一 REJECT→整文驳回）</p>
     */
    public static final String ARTICLE_REVIEW_AGG_KEY_PREFIX = "content:article:review-agg:";

    /** 聚合暂存 Hash field：文本链路结论 */
    public static final String ARTICLE_REVIEW_AGG_FIELD_TEXT = "text";

    /** 聚合暂存 Hash field：配图复审链路结论 */
    public static final String ARTICLE_REVIEW_AGG_FIELD_IMG = "img";

    /** 聚合暂存 TTL（秒）：短暂暂存等待双链路齐备，超时残留等 TTL 过期（终态 CAS 幂等兑底） */
    public static final long ARTICLE_REVIEW_AGG_TTL_SECONDS = 7200L;
}