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
     * file 侧草稿图引用维度（DictEnum name 口径，对齐 StorageBizTypeEnum.DRAFT_IMAGE）
     * <p>草稿期引用以 DRAFT_IMAGE+bizId=draftId 隔离登记，发布归属切换后
     * unbind 本维度并转 ARTICLE_IMG+bizId=articleId，防 bind/unbind 维度错配</p>
     */
    public static final String FILE_BIZ_TYPE_DRAFT_IMG = "DRAFT_IMAGE";

    /**
     * file 侧封面图引用维度（DictEnum name 口径，对齐 StorageBizTypeEnum.COVER）：
     * 文章封面以 COVER+bizId=articleId、草稿封面以 COVER+bizId=draftId 登记引用
     */
    public static final String FILE_BIZ_TYPE_COVER = "COVER";

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

    // ==================== 发布保稿：图片校验失败定位文案（P1 2026-10-28，错误信息携带位置供前端定位） ====================

    /**
     * 正文配图校验失败文案模板（%1$s=position_order 顺序号，%2$s=file_id）
     * <p>图片归属校验/指纹校验失败时，错误信息按此模板携带「第 N 张图片(file_id=xxx)」，
     * 前端可据此定位编辑器中对应的图片位置（P1 发布异常保稿）</p>
     */
    public static final String IMG_CHECK_FAIL_MSG = "第 %s 张图片(file_id=%s) 校验失败";

    /** 封面图校验失败文案模板（%s=file_id；封面无 position_order，仅带文件ID定位） */
    public static final String COVER_CHECK_FAIL_MSG = "封面图片(file_id=%s) 校验失败";

    /** 配图聚合指纹缺失文案模板（%s=缺失的 fileId 逗号串；指纹缺图时定位脏数据文件） */
    public static final String IMG_HASH_MISSING_MSG = "配图聚合指纹缺失(file_id=%s)：文件元数据为脏数据或文件已删除";

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

    /**
     * 浏览计数待刷回键前缀（拼接 articleId，String 存增量）——
     * 详情命中后 INCR 累计，定时任务 GETDEL 原子取出后刷回 article.view_cnt
     */
    public static final String VIEW_CNT_PENDING_KEY_PREFIX = "content:article:view:pending:";

    /**
     * 浏览防刷去重键前缀（拼接 articleId:visitorHash，SETNX+EXPIRE）——
     * 键存在=去重窗口期内重复进入不计数（匿名用 ip+UA 哈希归一）
     */
    public static final String VIEW_DEDUP_KEY_PREFIX = "content:article:view:dedup:";

    /** 浏览去重窗口 TTL（秒，默认 24h；口径：PV+去重窗口，非纯 UV） */
    public static final long VIEW_DEDUP_TTL_SECONDS = 86400L;
}