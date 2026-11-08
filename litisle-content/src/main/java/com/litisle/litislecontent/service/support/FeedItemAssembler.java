package com.litisle.litislecontent.service.support;

import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleStat;
import com.litisle.litislecontent.domain.entity.Book;
import com.litisle.litislecontent.domain.entity.BookStat;
import com.litisle.litislecontent.domain.vo.BookFeedItemVO;
import com.litisle.litislecontent.domain.vo.FeedItemVO;

/**
 * 推荐流条目装配器（批次十 10.1：老 /feed 与新流共用同一份实体转 VO 口径）
 * <p>为什么单独抽类：老 FeedServiceImpl 与新 FeedStreamService 都要装配卡片 VO，
 * 若各写一份，新增 zone/category 字段时容易只改一处，导致两条流字段口径漂移。</p>
 *
 * @author litisle
 */
public final class FeedItemAssembler {

    /** 私有构造：工具类禁止实例化 */
    private FeedItemAssembler() {
    }

    /**
     * 文章实体转文章流条目
     * <p>tags 固定不赋值：P0 不读 article_tag（批次十已确认决策），字段保留给 P1 标签偏好</p>
     *
     * @param article 文章实体（查询时已排除 content_html 大字段）
     * @return 文章流条目 VO
     */
    public static FeedItemVO toArticleItem(Article article) {
        return new FeedItemVO()
                .setArticleId(article.getId())
                .setTitle(article.getTitle())
                .setSummary(article.getSummary())
                .setCoverFileId(article.getCoverFileId())
                .setAuthorId(article.getAuthorId())
                .setZone(article.getZone())
                .setCategory(article.getCategory())
                .setViewCnt(article.getViewCnt())
                .setLikeCnt(article.getLikeCnt())
                .setPublishTime(article.getCreateTime());
    }

    /**
     * 文章实体转文章流条目（批次十一 11.2：计数改读 article_stat）
     *
     * <p>统计行缺失时三个计数统一按 0 展示（缺行=尚无互动，不是数据异常）；
     * 非空时以统计表为准，不再读 article 表的镜像列。</p>
     *
     * @param article 文章实体（查询时已排除 content_html 大字段）
     * @param stat    文章统计行（可为 null）
     * @return 文章流条目 VO
     */
    public static FeedItemVO toArticleItem(Article article, ArticleStat stat) {
        // 先复用基础字段装配，再用统计表覆盖三个计数
        FeedItemVO item = toArticleItem(article);
        item.setViewCnt(stat == null ? 0L : stat.getViewCount())
                .setLikeCnt(stat == null ? 0L : stat.getLikeCount())
                .setCommentCnt(stat == null ? 0L : stat.getCommentCount());
        return item;
    }

    /**
     * 书籍实体转书籍流条目
     * <p>chapterCount/collectCount/readerCount 由 10.4 聚合填充，此处保持 null（防前端把 0 当真值）</p>
     *
     * @param book 书籍实体
     * @return 书籍流条目 VO
     */
    public static BookFeedItemVO toBookItem(Book book) {
        return new BookFeedItemVO()
                .setId(book.getId())
                .setAuthorId(book.getAuthorId())
                .setTitle(book.getTitle())
                .setIntro(book.getIntro())
                .setCoverFileId(book.getCoverFileId())
                .setCategory(book.getCategory())
                .setStatus(book.getStatus())
                .setUpdateTime(book.getUpdateTime());
    }

    /**
     * 书籍实体转书籍流条目（批次十 10.4：带回三个聚合计数）
     *
     * @param book         书籍实体
     * @param chapterCount 章节数（book_chapter 聚合；查不到时为 null）
     * @param collectCount 收藏数（favorite target_type=2 聚合；查不到时为 null）
     * @param readerCount  阅读人数（reading_history biz_type=1 去重聚合；查不到时为 null）
     * @return 书籍流条目 VO
     */
    public static BookFeedItemVO toBookItem(Book book, Integer chapterCount, Long collectCount, Long readerCount) {
        return toBookItem(book)
                .setChapterCount(chapterCount)
                .setCollectCount(collectCount)
                .setReaderCount(readerCount);
    }

    /**
     * 书籍实体转书籍流条目（批次十一 11.2：三个计数改读 book_stat）
     *
     * <p>统计行缺失时三个计数统一按 0 展示（新书尚无人收藏/阅读且未挂章节）；
     * 非空时以统计表为准，不再实时聚合 favorite/reading_history/book_chapter。</p>
     *
     * @param book 书籍实体
     * @param stat 书籍统计行（可为 null）
     * @return 书籍流条目 VO
     */
    public static BookFeedItemVO toBookItem(Book book, BookStat stat) {
        // 章节数 VO 为 Integer：统计值为 Long，缺失按 0，避免 Long 转 Integer 溢出时抛异常
        Integer chapterCount = stat == null || stat.getChapterCount() == null
                ? 0 : Math.toIntExact(stat.getChapterCount());
        return toBookItem(book,
                chapterCount,
                stat == null || stat.getCollectCount() == null ? 0L : stat.getCollectCount(),
                stat == null || stat.getReaderCount() == null ? 0L : stat.getReaderCount());
    }
}
