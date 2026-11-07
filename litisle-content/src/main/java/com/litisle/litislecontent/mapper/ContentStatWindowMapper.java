package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.entity.ContentStatWindow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * content_stat_window 统计窗口 Mapper（批次十一 11.3）
 *
 * <p>职责：
 * ① 读侧按「目标类型 + ID 集合 + 窗口天数集合」批量取窗口行（一次 IN 查询，禁止逐条查）；
 * ② 写侧由每日任务全量 upsert 文章/书籍的窗口计数（从 favorite / reading_history 聚合）。</p>
 *
 * <p>注意：窗口任务以 article/book 现存行作为基准行（left join 聚合），
 * 没有互动的目标也会写入 0 值行——这样读侧能区分「窗口值为 0」与「窗口尚未计算」。</p>
 *
 * @author litisle
 */
@Mapper
public interface ContentStatWindowMapper extends BaseMapper<ContentStatWindow> {

    /**
     * 按目标类型 + ID 集合 + 窗口天数集合批量查窗口行。
     *
     * @param targetType 目标类型（1-文章 2-书籍）
     * @param targetIds  目标 ID 集合（调用方保证非空）
     * @param windowDays 窗口天数集合（默认 30、90）
     * @return 命中的窗口行（缺失的组合不会出现在结果中）
     */
    @Select("""
            <script>
            select id, target_type, target_id, window_days, collect_count, read_count, stat_update_time
            from content_stat_window
            where target_type = #{targetType}
              and target_id in
              <foreach collection="targetIds" item="targetId" open="(" separator="," close=")">#{targetId}</foreach>
              and window_days in
              <foreach collection="windowDays" item="day" open="(" separator="," close=")">#{day}</foreach>
            </script>
            """)
    List<ContentStatWindow> selectByTargets(@Param("targetType") Integer targetType,
                                            @Param("targetIds") Collection<Long> targetIds,
                                            @Param("windowDays") Collection<Integer> windowDays);

    /**
     * 全量重算并 upsert 文章窗口计数（同一窗口天数一条语句，幂等）。
     *
     * <p>基准行 = 未软删文章；left join favorite（target_type=1）与 reading_history（biz_type=2）
     * 的窗口内聚合。窗口内无互动写 0，用于区分「真 0」与「未计算」。</p>
     *
     * <p>收藏口径（批次十一 11.9）：聚合时排除作者自收藏（favorite.user_id = article.author_id），
     * 与写入侧「作者自收藏不 +1」保持一致，否则窗口值会与累计值口径打架。</p>
     *
     * @param windowDays 窗口天数
     * @param cutoff     窗口起点（当前时间减 windowDays 天）
     * @return 写入/更新的行数
     */
    @Insert("<script>"
            + "insert into content_stat_window "
            + "  (target_type, target_id, window_days, collect_count, read_count, stat_update_time) "
            + "select " + ContentBizConstant.STAT_WINDOW_TARGET_ARTICLE + ", a.id, #{windowDays}, "
            + "       coalesce(f.cnt, 0), coalesce(r.cnt, 0), now() "
            + "  from article a "
            + "  left join (select f.target_id, count(*) as cnt from favorite f "
            + "              join article a2 on a2.id = f.target_id "
            + "               and a2.author_id &lt;&gt; f.user_id "
            + "              where f.target_type = " + ContentBizConstant.STAT_WINDOW_TARGET_ARTICLE + " "
            + "                and f.create_time &gt;= #{cutoff} "
            + "              group by f.target_id) f on f.target_id = a.id "
            + "  left join (select biz_id, count(distinct user_id) as cnt from reading_history "
            + "              where biz_type = " + ContentBizConstant.READING_BIZ_TYPE_ARTICLE + " "
            + "                and update_time &gt;= #{cutoff} "
            + "              group by biz_id) r on r.biz_id = a.id "
            + " where a.del_flag = 0 "
            + "on duplicate key update "
            + "  collect_count = values(collect_count), "
            + "  read_count = values(read_count), "
            + "  stat_update_time = now() "
            + "</script>")
    int upsertArticleWindow(@Param("windowDays") Integer windowDays,
                            @Param("cutoff") LocalDateTime cutoff);

    /**
     * 全量重算并 upsert 书籍窗口计数（同一窗口天数一条语句，幂等）。
     *
     * <p>基准行 = 未软删书籍；left join favorite（target_type=2）与 reading_history（biz_type=1）
     * 的窗口内聚合（阅读人数按 user_id 去重）。</p>
     *
     * <p>收藏口径（批次十一 11.9）：聚合时排除作者自收藏（favorite.user_id = book.author_id），
     * 与写入侧「作者自收藏不 +1」保持一致。</p>
     *
     * @param windowDays 窗口天数
     * @param cutoff     窗口起点（当前时间减 windowDays 天）
     * @return 写入/更新的行数
     */
    @Insert("<script>"
            + "insert into content_stat_window "
            + "  (target_type, target_id, window_days, collect_count, read_count, stat_update_time) "
            + "select " + ContentBizConstant.STAT_WINDOW_TARGET_BOOK + ", b.id, #{windowDays}, "
            + "       coalesce(f.cnt, 0), coalesce(r.cnt, 0), now() "
            + "  from book b "
            + "  left join (select f.target_id, count(*) as cnt from favorite f "
            + "              join book b2 on b2.id = f.target_id "
            + "               and b2.author_id &lt;&gt; f.user_id "
            + "              where f.target_type = " + ContentBizConstant.STAT_WINDOW_TARGET_BOOK + " "
            + "                and f.create_time &gt;= #{cutoff} "
            + "              group by f.target_id) f on f.target_id = b.id "
            + "  left join (select biz_id, count(distinct user_id) as cnt from reading_history "
            + "              where biz_type = " + ContentBizConstant.READING_BIZ_TYPE_BOOK + " "
            + "                and update_time &gt;= #{cutoff} "
            + "              group by biz_id) r on r.biz_id = b.id "
            + " where b.del_flag = 0 "
            + "on duplicate key update "
            + "  collect_count = values(collect_count), "
            + "  read_count = values(read_count), "
            + "  stat_update_time = now() "
            + "</script>")
    int upsertBookWindow(@Param("windowDays") Integer windowDays,
                         @Param("cutoff") LocalDateTime cutoff);
}
