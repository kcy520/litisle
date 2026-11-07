package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.ArticleStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * article_stat 文章互动统计 Mapper（批次十一 11.1）
 *
 * <p>所有计数变更都是单条 SQL 原子操作：</p>
 * <ul>
 *   <li>加计数：{@code INSERT ... ON DUPLICATE KEY UPDATE cnt = cnt + 1}，缺行自动补建，不因缺行失败；</li>
 *   <li>减计数：{@code SET cnt = GREATEST(cnt - 1, 0)} 且带 {@code cnt > 0} 条件，防止连点取消把计数减成负数；</li>
 *   <li>绝对值同步（浏览量）：覆盖写最新值，只由浏览计数刷回任务调用。</li>
 * </ul>
 *
 * @author litisle
 */
@Mapper
public interface ArticleStatMapper extends BaseMapper<ArticleStat> {

    /**
     * 收藏数原子 +1（统计行不存在时自动补建）。
     *
     * @param articleId 文章ID
     * @return 影响行数（1=新增或自增成功）
     */
    @Insert("""
            insert into article_stat (article_id, collect_count, stat_update_time)
            values (#{articleId}, 1, now())
            on duplicate key update
                collect_count = collect_count + 1,
                stat_update_time = now()
            """)
    int increaseCollect(@Param("articleId") Long articleId);

    /**
     * 收藏数原子 -1（带下限保护：计数已为 0 或行不存在时不生效）。
     *
     * @param articleId 文章ID
     * @return 影响行数（0=无需递减，按幂等成功处理）
     */
    @Update("""
            update article_stat
               set collect_count = greatest(collect_count - 1, 0),
                   stat_update_time = now()
             where article_id = #{articleId}
               and collect_count > 0
            """)
    int decreaseCollect(@Param("articleId") Long articleId);

    /**
     * 点赞数原子 +1（批次十一 11.1 只提供入口，11.8 点赞接口调用）。
     *
     * @param articleId 文章ID
     * @return 影响行数
     */
    @Insert("""
            insert into article_stat (article_id, like_count, stat_update_time)
            values (#{articleId}, 1, now())
            on duplicate key update
                like_count = like_count + 1,
                stat_update_time = now()
            """)
    int increaseLike(@Param("articleId") Long articleId);

    /**
     * 点赞数原子 -1（带下限保护，取消点赞复用）。
     *
     * @param articleId 文章ID
     * @return 影响行数（0=无需递减，按幂等成功处理）
     */
    @Update("""
            update article_stat
               set like_count = greatest(like_count - 1, 0),
                   stat_update_time = now()
             where article_id = #{articleId}
               and like_count > 0
            """)
    int decreaseLike(@Param("articleId") Long articleId);

    /**
     * 浏览量绝对值同步（ViewCountFlushJob 落库后调用；覆盖写最新值，缺行补建）。
     *
     * @param articleId 文章ID
     * @param viewCount 落库后的最新浏览量绝对值
     * @return 影响行数
     */
    @Insert("""
            insert into article_stat (article_id, view_count, stat_update_time)
            values (#{articleId}, #{viewCount}, now())
            on duplicate key update
                view_count = #{viewCount},
                stat_update_time = now()
            """)
    int upsertViewCount(@Param("articleId") Long articleId, @Param("viewCount") Long viewCount);
}
