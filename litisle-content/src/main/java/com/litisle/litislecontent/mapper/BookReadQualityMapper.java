package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.entity.BookReadQuality;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/**
 * book_read_quality 粗完读率统计 Mapper（批次十一 11.13）
 *
 * <p>职责：一条 SQL 全量重算所有书籍的「完读人数 / 有效阅读人数」并 upsert。
 * 只由定时任务调用，禁止在翻页/详情请求中现算。</p>
 *
 * @author litisle
 */
@Mapper
public interface BookReadQualityMapper extends BaseMapper<BookReadQuality> {

    /**
     * 全量重算并 upsert 书籍粗完读率。
     *
     * <p>口径：</p>
     * <ul>
     *   <li>基准行 = book 全表（无阅读历史的书也写 0/0，避免旧值残留）；</li>
     *   <li>分子：最后一次阅读章节号 ≥ 该书当前最大「公开可见」章节号的读者数；</li>
     *   <li>分母：最后一次阅读章节可解析到该书公开可见章节的读者数（与分子同源，不使用累计口径）；</li>
     *   <li>章节可见性只按 visibility=公开 + 已发布 + 未删判定；仅关注者可见章节需要社交关系，
     *       纯 SQL 不做逐用户判定（误差已在实体注释写明）。</li>
     * </ul>
     *
     * @return 写入/更新的书籍行数
     */
    @Insert("""
            <script>
            insert into book_read_quality
                (book_id, finish_reader_count, eligible_reader_count, stat_update_time)
            select b.id,
                   coalesce(q.finish_cnt, 0),
                   coalesce(q.eligible_cnt, 0),
                   now()
              from book b
              left join (
                    select h.biz_id as book_id,
                           sum(case when bc.chapter_no &gt;= m.max_no then 1 else 0 end) as finish_cnt,
                           count(distinct h.user_id) as eligible_cnt
                      from reading_history h
                      join book_chapter bc
                        on bc.id = h.chapter_id
                       and bc.book_id = h.biz_id
                      join article a
                        on a.id = bc.article_id
                       and a.status = """ + ContentBizConstant.ARTICLE_STATUS_PUBLISHED + " " + """
                       and a.del_flag = """ + ContentBizConstant.ARTICLE_DEL_FLAG_NOT_DELETED + " " + """
                       and a.visibility = """ + ContentBizConstant.ARTICLE_VISIBILITY_PUBLIC + " " + """
                      join (
                            select bc2.book_id, max(bc2.chapter_no) as max_no
                              from book_chapter bc2
                              join article a2
                                on a2.id = bc2.article_id
                               and a2.status = """ + ContentBizConstant.ARTICLE_STATUS_PUBLISHED + " " + """
                               and a2.del_flag = """ + ContentBizConstant.ARTICLE_DEL_FLAG_NOT_DELETED + " " + """
                               and a2.visibility = """ + ContentBizConstant.ARTICLE_VISIBILITY_PUBLIC + " " + """
                             group by bc2.book_id
                      ) m on m.book_id = h.biz_id
                     where h.biz_type = """ + ContentBizConstant.READING_BIZ_TYPE_BOOK + " " + """
                     group by h.biz_id
              ) q on q.book_id = b.id
            on duplicate key update
                finish_reader_count = values(finish_reader_count),
                eligible_reader_count = values(eligible_reader_count),
                stat_update_time = now()
            </script>
            """)
    int upsertFull();
}
