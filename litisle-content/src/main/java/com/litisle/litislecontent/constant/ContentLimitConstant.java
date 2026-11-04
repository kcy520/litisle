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

    /** 阅读历史上报（60s 2 次，配合前端 30s 节流；仅前进不回退在业务层校验） */
    public static final int HISTORY_MAX_COUNT = 2;

    /** 收藏/取消收藏（60s 20 次，防刷收藏写库；取消收藏同限频） */
    public static final int FAVORITE_MAX_COUNT = 20;

    /** 推荐流（60s 60 次，读多） */
    public static final int FEED_MAX_COUNT = 60;

    /** 标签前缀搜索（60s 30 次，矩阵外补充防爬） */
    public static final int TAG_SEARCH_MAX_COUNT = 30;

    /** 单篇文章标签数上限（§五配置 tag.max-per-article=5，防关键词堆砌） */
    public static final int TAG_MAX_PER_ARTICLE = 5;

    /** 热门标签 TOP N 条数（GET /tags/hot 返回上限） */
    public static final int TAG_HOT_TOP_N = 10;

    /** 标签前缀搜索单次返回上限（输入联想防全池拖出） */
    public static final int TAG_SEARCH_RESULT_LIMIT = 10;

    /** 标签名长度上限（字）：与词库词条同口径 ≤64，超长入参直接拒绝不进 DFA */
    public static final int TAG_NAME_MAX_LENGTH = 64;

    /** 热门标签缓存 TTL 基准（秒）：实际写入 ±10% 抖动防雪崩 */
    public static final long TAG_HOT_CACHE_TTL_SECONDS = 300;

    /** 正文长度硬上限（字节）：content_html 为 LONGTEXT 无 DB 约束，应用层兜底拦截超大正文
     * （防打爆送审回查/search 索引/ai RAG 摄入）；≈10 万字富文本 HTML，发布/建档/编辑/草稿保存四路共用同值 */
    public static final int ARTICLE_CONTENT_MAX_BYTES = 256 * 1024;

    /** 单用户阅读历史条数上限（按 biz_type 分开计，各 500 条）：写入时懒删最旧行（按最后阅读时间），
     * 防 reading_history 无限增长；随写入路径走，零新增 job、零 yml 配置 */
    public static final int HISTORY_MAX_PER_USER = 500;

    /** 单本书章节数上限（防滥用兜底，防单书无限挂章拖垮章节列表/重排批量更新；
     * Sql-Document book_chapter 建表注释同口径） */
    public static final int BOOK_CHAPTER_MAX = 1000;

    /** 章节重排（60s 10 次，批量写操作防频繁拖拽提交） */
    public static final int REORDER_MAX_COUNT = 10;

    /** 章节重排两阶段偏移的临时区偏移量：阶段①全部涉及行 chapter_no += 本值挪进临时区
     * （避开 uk_book_chapter_no 唯一键冲突），阶段②再落正值为新序号；
     * 取值远大于 BOOK_CHAPTER_MAX 且 Service 侧预检存量序号 < 本值，保证临时区不与正序区重叠 */
    public static final int BOOK_CHAPTER_REORDER_OFFSET = 10_000_000;
}
