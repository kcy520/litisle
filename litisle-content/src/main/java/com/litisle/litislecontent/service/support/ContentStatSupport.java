package com.litisle.litislecontent.service.support;

import com.litisle.litislecontent.enums.FavoriteTargetTypeEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.ArticleStatMapper;
import com.litisle.litislecontent.mapper.BookStatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 互动统计写入统一入口（批次十一 11.1）
 *
 * <p>作用：把收藏、首次阅读、点赞、浏览量的计数变更统一落到 article_stat/book_stat，
 * 读侧（推荐流排序、热度榜、列表展示）之后只读统计表，不再实时聚合源表。</p>
 *
 * <p>写入纪律：</p>
 * <ul>
 *   <li>计数只走 SQL 原子自增/自减，禁止「先查后写」（并发会丢更新）；</li>
 *   <li>统计行不存在时由 upsert 补建，不让写入因缺行失败；</li>
 *   <li>收藏加减与收藏记录同事务，异常向上抛由调用方事务回滚；</li>
 *   <li>文章点赞加减与 article.like_cnt 镜像同事务同步，保证详情页、search 首次建索引、11.16 对账三方一致；</li>
 *   <li>阅读人数/浏览量属非账务热度信号，调用方允许 fail-safe（失败告警，由对账兜底）。</li>
 * </ul>
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class ContentStatSupport {

    /** 文章统计 Mapper（article_stat 原子计数入口） */
    private final ArticleStatMapper articleStatMapper;

    /** 书籍统计 Mapper（book_stat 原子计数入口） */
    private final BookStatMapper bookStatMapper;

    /** 文章 Mapper（article.like_cnt 镜像同步：详情页/建索引/对账仍读该冗余列，批次十一 11.14） */
    private final ArticleMapper articleMapper;

    /**
     * 收藏数 +1（按收藏目标类型路由到文章或书籍统计表）。
     *
     * @param targetType 收藏目标类型（ARTICLE=文章 / BOOK=书籍）
     * @param targetId   目标ID（文章ID或书籍ID）
     */
    public void increaseCollect(FavoriteTargetTypeEnum targetType, Long targetId) {
        if (targetType == FavoriteTargetTypeEnum.ARTICLE) {
            articleStatMapper.increaseCollect(targetId);
        } else {
            bookStatMapper.increaseCollect(targetId);
        }
    }

    /**
     * 收藏数 -1（按目标类型路由；统计行缺失或计数已为 0 时不生效，按幂等成功处理）。
     *
     * @param targetType 收藏目标类型（ARTICLE=文章 / BOOK=书籍）
     * @param targetId   目标ID（文章ID或书籍ID）
     */
    public void decreaseCollect(FavoriteTargetTypeEnum targetType, Long targetId) {
        if (targetType == FavoriteTargetTypeEnum.ARTICLE) {
            articleStatMapper.decreaseCollect(targetId);
        } else {
            bookStatMapper.decreaseCollect(targetId);
        }
    }

    /**
     * 点赞数 +1（批次十一 11.8：点赞记录首次插入成功后调用）。
     *
     * <p>按目标类型路由到 article_stat / book_stat；重复点赞命中唯一键时不调用本方法，避免重复加计数。</p>
     *
     * @param targetType 点赞目标类型（ARTICLE=文章 / BOOK=书籍）
     * @param targetId   目标ID（文章ID或书籍ID）
     */
    public void increaseLike(FavoriteTargetTypeEnum targetType, Long targetId) {
        if (targetType == FavoriteTargetTypeEnum.ARTICLE) {
            articleStatMapper.increaseLike(targetId);
            // 同步 article.like_cnt 镜像（详情页 VO、search 首次建索引、11.16 对账仍读该列）
            articleMapper.increaseLikeCnt(targetId);
        } else {
            bookStatMapper.increaseLike(targetId);
        }
    }

    /**
     * 点赞数 -1（批次十一 11.8：取消点赞物理删除影响行数 > 0 后调用，带下限保护）。
     *
     * <p>按目标类型路由到 article_stat / book_stat；统计行缺失或计数已为 0 时 SQL 不生效，
     * 由 GREATEST(cnt-1,0) 保证不会减成负数。</p>
     *
     * @param targetType 点赞目标类型（ARTICLE=文章 / BOOK=书籍）
     * @param targetId   目标ID（文章ID或书籍ID）
     */
    public void decreaseLike(FavoriteTargetTypeEnum targetType, Long targetId) {
        if (targetType == FavoriteTargetTypeEnum.ARTICLE) {
            articleStatMapper.decreaseLike(targetId);
            // 同步 article.like_cnt 镜像（递减同样带 GREATEST 下限，避免镜像减成负数）
            articleMapper.decreaseLikeCnt(targetId);
        } else {
            bookStatMapper.decreaseLike(targetId);
        }
    }

    /**
     * 阅读人数 +1（只在「该用户首次读这本书」时调用）。
     *
     * <p>首次判定由 reading_history 唯一键 uk_user_biz 保证：并发首写只有一条插入成功，
     * 重复阅读走更新分支不调用本方法。</p>
     *
     * @param bookId 书籍ID
     */
    public void increaseReader(Long bookId) {
        bookStatMapper.increaseReader(bookId);
    }

    /**
     * 章节数 +1（批次十一 11.2：书籍挂载新章节后调用）。
     *
     * <p>口径（批次十一 11.3 修正）：只有「未删 + 已发布 + 公开」的可见章节才计数，调用方负责判定。
     * 章节数是展示型计数，调用方允许 fail-safe（失败只告警，由回填脚本/对账兜底）。</p>
     *
     * @param bookId 书籍ID
     */
    public void increaseChapter(Long bookId) {
        bookStatMapper.increaseChapter(bookId);
    }

    /**
     * 章节数 -1（批次十一 11.2：移除章节后调用，带下限保护）。
     *
     * <p>口径（批次十一 11.3 修正）：调用方只在被移除章节当时属于「未删 + 已发布 + 公开」时调用，
     * 避免把从未计入的私密/未过审章节误减。</p>
     *
     * @param bookId 书籍ID
     */
    public void decreaseChapter(Long bookId) {
        bookStatMapper.decreaseChapter(bookId);
    }

    /**
     * 浏览量同步（浏览计数刷回落库 article.view_cnt 后，把最新绝对值同步到统计表）。
     *
     * @param articleId 文章ID
     * @param viewCount 落库后的最新浏览量绝对值
     */
    public void syncViewCount(Long articleId, long viewCount) {
        articleStatMapper.upsertViewCount(articleId, viewCount);
    }
}
