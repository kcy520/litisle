package com.litisle.litislecontent.constant;

/**
 * content 模块限流常量（@RateLimiter 参数统一收口，对齐 file 模块 FileLimitConstant 模式）
 * <p>限流矩阵来源：Content_Function_Develop.md §八（窗口统一 60s，阈值后续可外置 yml）</p>
 *
 * @author litisle
 */
public final class ContentLimitConstant {

    /** 私有构造：常量类禁止实例化 */
    private ContentLimitConstant() {
    }

    /** 限流窗口（秒）——全矩阵统一 60s（各 Controller @RateLimiter(time=...) 静态导入用） */
    public static final int LIMIT_WINDOW_SECONDS = 60;

    /** 发布/重提（60s 10 次） */
    public static final int PUBLISH_MAX_COUNT = 10;

    /** 草稿自动保存（60s 10 次，防刷库） */
    public static final int DRAFT_SAVE_MAX_COUNT = 10;

    /** 批量发布（60s 3 次，批量重操作；单批 ≤20 上限在业务层校验） */
    public static final int BATCH_PUBLISH_MAX_COUNT = 3;

    /** 发布前预检（60s 10 次） */
    public static final int PREVIEW_CHECK_MAX_COUNT = 10;

    /** 密码访问校验（60s 5 次，防爆破，对齐 auth 登录防爆破） */
    public static final int VERIFY_PASSWORD_MAX_COUNT = 5;

    /** 阅读进度上报（60s 2 次，配合前端 30s 节流；仅前进不回退在业务层校验） */
    public static final int PROGRESS_MAX_COUNT = 2;

    /** 推荐流（60s 60 次，读多） */
    public static final int FEED_MAX_COUNT = 60;

    /** 标签前缀搜索（60s 30 次，矩阵外补充防爬） */
    public static final int TAG_SEARCH_MAX_COUNT = 30;
}
