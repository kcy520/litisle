package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.BookStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * book_stat 书籍互动统计 Mapper（批次十一 11.1）
 *
 * <p>计数变更语义与 ArticleStatMapper 一致：加计数用 upsert 原子自增，减计数带下限保护；
 * reader_count 只增不减，重复阅读由调用方（reading_history 唯一键）保证不会重复加。</p>
 *
 * @author litisle
 */
@Mapper
public interface BookStatMapper extends BaseMapper<BookStat> {

    /**
     * 收藏数原子 +1（统计行不存在时自动补建）。
     *
     * @param bookId 书籍ID
     * @return 影响行数
     */
    @Insert("""
            insert into book_stat (book_id, collect_count, stat_update_time)
            values (#{bookId}, 1, now())
            on duplicate key update
                collect_count = collect_count + 1,
                stat_update_time = now()
            """)
    int increaseCollect(@Param("bookId") Long bookId);

    /**
     * 收藏数原子 -1（带下限保护）。
     *
     * @param bookId 书籍ID
     * @return 影响行数（0=无需递减，按幂等成功处理）
     */
    @Update("""
            update book_stat
               set collect_count = greatest(collect_count - 1, 0),
                   stat_update_time = now()
             where book_id = #{bookId}
               and collect_count > 0
            """)
    int decreaseCollect(@Param("bookId") Long bookId);

    /**
     * 阅读人数原子 +1（仅在该用户首次读这本书时调用）。
     *
     * @param bookId 书籍ID
     * @return 影响行数
     */
    @Insert("""
            insert into book_stat (book_id, reader_count, stat_update_time)
            values (#{bookId}, 1, now())
            on duplicate key update
                reader_count = reader_count + 1,
                stat_update_time = now()
            """)
    int increaseReader(@Param("bookId") Long bookId);

    /**
     * 章节数原子 +1（批次十一 11.2：挂载新章节后同步书籍卡片计数）。
     *
     * <p>调用方必须只对「公开可见章节」（未删 + 已发布 + 公开）调用（批次十一 11.3 口径修正）；
     * 私密/仅关注者/未过审章节不计入卡片章节数。</p>
     *
     * @param bookId 书籍ID
     * @return 影响行数
     */
    @Insert("""
            insert into book_stat (book_id, chapter_count, stat_update_time)
            values (#{bookId}, 1, now())
            on duplicate key update
                chapter_count = chapter_count + 1,
                stat_update_time = now()
            """)
    int increaseChapter(@Param("bookId") Long bookId);

    /**
     * 章节数原子 -1（带下限保护，移除章节时调用）。
     *
     * <p>调用方必须只在该章节当时属于「公开可见章节」时调用（批次十一 11.3 口径修正），
     * 否则会把从未计入的私密/未过审章节误减掉。</p>
     *
     * @param bookId 书籍ID
     * @return 影响行数（0=统计行缺失或已为 0，按幂等成功处理）
     */
    @Update("""
            update book_stat
               set chapter_count = greatest(chapter_count - 1, 0),
                   stat_update_time = now()
             where book_id = #{bookId}
               and chapter_count > 0
            """)
    int decreaseChapter(@Param("bookId") Long bookId);

    /**
     * 点赞数原子 +1（统计行不存在时自动补建，批次十一 11.8）。
     *
     * @param bookId 书籍ID
     * @return 影响行数
     */
    @Insert("""
            insert into book_stat (book_id, like_count, stat_update_time)
            values (#{bookId}, 1, now())
            on duplicate key update
                like_count = like_count + 1,
                stat_update_time = now()
            """)
    int increaseLike(@Param("bookId") Long bookId);

    /**
     * 点赞数原子 -1（带下限保护，取消点赞物理删除成功后才调用）。
     *
     * @param bookId 书籍ID
     * @return 影响行数（0=统计行缺失或已为 0，按幂等成功处理）
     */
    @Update("""
            update book_stat
               set like_count = greatest(like_count - 1, 0),
                   stat_update_time = now()
             where book_id = #{bookId}
               and like_count > 0
            """)
    int decreaseLike(@Param("bookId") Long bookId);
}
