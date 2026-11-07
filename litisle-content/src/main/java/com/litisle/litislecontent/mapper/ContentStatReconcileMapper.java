package com.litisle.litislecontent.mapper;

import com.litisle.litislecontent.domain.dto.ArticleCounterDiffDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 互动统计对账查询 Mapper（批次十一 11.16）
 *
 * <p>作用：提供统计表与源表的差异行数查询。只读、不修正数据；
 * 差异由 ContentStatReconcileJob 写日志并打指标，修正策略另行确定。</p>
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>收藏/浏览量/点赞/评论做双向差异（源表有、统计表有但值不同，以及统计表残留而源表已无）；</li>
 *   <li>收藏源值排除作者自收藏（批次十一 11.9）：写入与窗口任务都不计作者本人那一条，
 *       对账源值若仍按「全部收藏行」聚合会永久误报，故此处按 favorite.user_id &lt;&gt; article.author_id /
 *       book.author_id 过滤，改后差异应为 0；</li>
 *   <li>reader_count 只查「源表大于统计表」的少计方向：reading_history 已按 500 条裁剪，
 *       源表偏小属预期，若反而要求相等会持续误报；</li>
 *   <li>chapter_count 暂不参与对账：11.2 已接入挂章/移除章节的同步写入，对账维度留待后续补充。</li>
 * </ul>
 *
 * @author litisle
 */
@Mapper
public interface ContentStatReconcileMapper {

    /**
     * 文章收藏数差异行数（源表有收藏记录的每个目标逐个比对）。
     * <p>源值按 11.9 口径排除作者自收藏：只数 favorite.user_id &lt;&gt; article.author_id 的行。</p>
     *
     * @return 差异行数
     */
    @Select("""
            select count(*)
              from (
                    select s.target_id as id, s.cnt as src, coalesce(a.collect_count, 0) as stat
                      from (select f.target_id, count(*) as cnt
                              from favorite f
                              join article a2 on a2.id = f.target_id
                               and a2.author_id <> f.user_id
                             where f.target_type = 1
                             group by f.target_id) s
                      left join article_stat a on a.article_id = s.target_id
                     where coalesce(a.collect_count, 0) <> s.cnt
                   ) t
            """)
    long countArticleCollectSourceDiff();

    /**
     * 文章收藏数差异行数（统计表有计数但源表已无收藏记录的残留行）。
     * <p>源值同样排除作者自收藏；「只有作者自收藏」的目标源值视为无记录，
     * 统计值应为 0，若不为 0 说明写入侧漏判，会在这里暴露出来。</p>
     *
     * @return 差异行数
     */
    @Select("""
            select count(*)
              from article_stat a
              left join (select f.target_id, count(*) as cnt
                           from favorite f
                           join article a2 on a2.id = f.target_id
                            and a2.author_id <> f.user_id
                          where f.target_type = 1
                          group by f.target_id) s
                on s.target_id = a.article_id
             where s.target_id is null
               and a.collect_count <> 0
            """)
    long countArticleCollectStatOnlyDiff();

    /**
     * 书籍收藏数差异行数（源表有收藏记录的每个目标逐个比对）。
     * <p>源值按 11.9 口径排除作者自收藏：只数 favorite.user_id &lt;&gt; book.author_id 的行。</p>
     *
     * @return 差异行数
     */
    @Select("""
            select count(*)
              from (
                    select s.target_id as id, s.cnt as src, coalesce(b.collect_count, 0) as stat
                      from (select f.target_id, count(*) as cnt
                              from favorite f
                              join book b2 on b2.id = f.target_id
                               and b2.author_id <> f.user_id
                             where f.target_type = 2
                             group by f.target_id) s
                      left join book_stat b on b.book_id = s.target_id
                     where coalesce(b.collect_count, 0) <> s.cnt
                   ) t
            """)
    long countBookCollectSourceDiff();

    /**
     * 书籍收藏数差异行数（统计表有计数但源表已无收藏记录的残留行）。
     * <p>源值同样排除作者自收藏（判定逻辑同文章侧）。</p>
     *
     * @return 差异行数
     */
    @Select("""
            select count(*)
              from book_stat b
              left join (select f.target_id, count(*) as cnt
                           from favorite f
                           join book b2 on b2.id = f.target_id
                            and b2.author_id <> f.user_id
                          where f.target_type = 2
                          group by f.target_id) s
                on s.target_id = b.book_id
             where s.target_id is null
               and b.collect_count <> 0
            """)
    long countBookCollectStatOnlyDiff();

    /**
     * 文章浏览量/点赞/评论差异行数（一次查询返回三项，避免三次全表扫描）。
     *
     * @return 三项差异行数（无差异时均为 0）
     */
    @Select("""
            select
                cast(coalesce(sum(case when coalesce(s.view_count, 0) <> a.view_cnt then 1 else 0 end), 0) as signed) as viewDiff,
                cast(coalesce(sum(case when coalesce(s.like_count, 0) <> a.like_cnt then 1 else 0 end), 0) as signed) as likeDiff,
                cast(coalesce(sum(case when coalesce(s.comment_count, 0) <> a.comment_cnt then 1 else 0 end), 0) as signed) as commentDiff
              from article a
              left join article_stat s on s.article_id = a.id
             where a.del_flag = 0
            """)
    ArticleCounterDiffDTO countArticleCounterDiff();

    /**
     * 书籍阅读人数少计差异行数（只查「源表阅读人数 > 统计表」的方向）。
     *
     * @return 少计行数
     */
    @Select("""
            select count(*)
              from (
                    select s.target_id as id, s.cnt as src, coalesce(b.reader_count, 0) as stat
                      from (select biz_id as target_id, count(*) as cnt from reading_history where biz_type = 1 group by biz_id) s
                      left join book_stat b on b.book_id = s.target_id
                     where s.cnt > coalesce(b.reader_count, 0)
                   ) t
            """)
    long countBookReaderUnderDiff();
}
