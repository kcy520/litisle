package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.litislecontent.domain.entity.BookChapter;
import com.litisle.litislecontent.domain.vo.BookChapterVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * @Mapper + extends BaseMapper<BookChapter>（MyBatis-Plus）
 *
 * 关键查询：
 * - 章节目录：selectList(book_id，order by chapter_no asc) —— 序号即排序键，目录页一次取全
 * - 挂载防重：insert(book_id, chapter_no, article_id) —— (book_id, chapter_no) 唯一键
 *   uk_book_chapter_no 兜底，业务层捕获冲突重取最大序号+1 重试
 * - 反查章节归属：selectByArticleId(article_id) —— 文章是否已被挂书、挂到哪本书
 *   （发布校验/退章/阅读落历史的归属判定；limit 2 覆盖 0/1/多书归属判定，禁止只取第一条）
 * - 退章/重排：delete(book_id + article_id 或 chapter_no) —— 重排为同事务先删后插，
 *   reading_history 里指向被移除 chapter 的历史行由业务层容错（章节缺失按最新章处理）
 * - 可见性章节查询（阶段17）：selectVisibleChapterPage / countVisibleChapters /
 *   selectFirstVisible / selectLatestVisible / selectVisibleChapter / selectPrevVisible /
 *   selectNextVisible —— 统一 join article 过滤「已发布 + 未删 + visibility IN (visibilities)」，
 *   visibilities 由 Service 按「作者(1,2,3全可见)/匿名或非关注者(1)/关注者(1,3)」计算，
 *   SQL 用 &lt;foreach&gt; 拼 IN；分页/相邻章节/首尾章均由 SQL 层完成
 * - 关注章节存在性：existsFollowersChapter —— 书内是否存在 visibility=3 的已发布章节，
 *   用于登录用户进书时决定是否回查关注关系（无关注章节不打 social，省一次跨服务）
 */
@Mapper
public interface BookChapterMapper extends BaseMapper<BookChapter> {

    /**
     * 按文章ID反查其章节挂载行（limit 2：最多取 2 条即可判定 0/1/多书归属）
     *
     * <p>用途：阅读即落历史的归属判定——0 条=独立文章（落文章历史）、
     * 1 条=章节文章（落该书的书籍历史）、2 条=多书挂载歧义（不自动落历史，交书籍阅读页显式上报）。
     * 禁止只取第一条：多书挂载时会把历史记到错误书籍。article_id 等值查询走 idx_article_id 索引。</p>
     *
     * @param articleId 文章ID
     * @return 挂载行列表（0/1/2 条；取满 2 条即按多书归属歧义处理）
     */
    @Select("select * from book_chapter where article_id = #{articleId} limit 2")
    List<BookChapter> selectByArticleId(@Param("articleId") Long articleId);

    /**
     * 可见章节分页（章节列表接口承接方）。
     *
     * <p>join article 过滤「已发布(status=2) + 未删(del_flag=0) + visibility IN (visibilities)」，
     * order by chapter_no asc（uk_book_chapter_no 序号即排序键），SQL 层完成分页与 total。</p>
     *
     * @param page         MyBatis-Plus 分页参数（Page.of 构造）
     * @param bookId       书籍ID
     * @param visibilities 可见性范围（VisibilityEnum code 集合，由 Service 计算，禁止为空集合）
     * @return 章节列表项分页（chapterId/articleId/chapterNo/title）
     */
    @Select("""
            <script>
            select bc.id as chapterId, bc.article_id as articleId,
                   bc.chapter_no as chapterNo, a.title as title
            from book_chapter bc
            join article a on a.id = bc.article_id
            where bc.book_id = #{bookId}
              and a.status = 2
              and a.del_flag = 0
              and a.visibility in
              <foreach collection="visibilities" item="v" open="(" separator="," close=")">#{v}</foreach>
            order by bc.chapter_no asc
            </script>
            """)
    Page<BookChapterVO> selectVisibleChapterPage(Page<BookChapterVO> page,
                                                 @Param("bookId") Long bookId,
                                                 @Param("visibilities") Collection<Integer> visibilities);

    /**
     * 可见章节数（书籍详情 chapterCount 承接方，过滤口径与分页一致）。
     *
     * @param bookId       书籍ID
     * @param visibilities 可见性范围
     * @return 可见章节总数
     */
    @Select("""
            <script>
            select count(*)
            from book_chapter bc
            join article a on a.id = bc.article_id
            where bc.book_id = #{bookId}
              and a.status = 2
              and a.del_flag = 0
              and a.visibility in
              <foreach collection="visibilities" item="v" open="(" separator="," close=")">#{v}</foreach>
            </script>
            """)
    Long countVisibleChapters(@Param("bookId") Long bookId,
                              @Param("visibilities") Collection<Integer> visibilities);

    /**
     * 第一章可见章节（书籍详情 firstChapter 承接方，chapter_no asc limit 1）。
     *
     * @param bookId       书籍ID
     * @param visibilities 可见性范围
     * @return 第一章；无可见章节返回 null
     */
    @Select("""
            <script>
            select bc.id as chapterId, bc.article_id as articleId,
                   bc.chapter_no as chapterNo, a.title as title
            from book_chapter bc
            join article a on a.id = bc.article_id
            where bc.book_id = #{bookId}
              and a.status = 2
              and a.del_flag = 0
              and a.visibility in
              <foreach collection="visibilities" item="v" open="(" separator="," close=")">#{v}</foreach>
            order by bc.chapter_no asc
            limit 1
            </script>
            """)
    BookChapterVO selectFirstVisible(@Param("bookId") Long bookId,
                                     @Param("visibilities") Collection<Integer> visibilities);

    /**
     * 最新一章可见章节（书籍详情 latestChapter 承接方，chapter_no desc limit 1）。
     *
     * @param bookId       书籍ID
     * @param visibilities 可见性范围
     * @return 最新章；无可见章节返回 null
     */
    @Select("""
            <script>
            select bc.id as chapterId, bc.article_id as articleId,
                   bc.chapter_no as chapterNo, a.title as title
            from book_chapter bc
            join article a on a.id = bc.article_id
            where bc.book_id = #{bookId}
              and a.status = 2
              and a.del_flag = 0
              and a.visibility in
              <foreach collection="visibilities" item="v" open="(" separator="," close=")">#{v}</foreach>
            order by bc.chapter_no desc
            limit 1
            </script>
            """)
    BookChapterVO selectLatestVisible(@Param("bookId") Long bookId,
                                      @Param("visibilities") Collection<Integer> visibilities);

    /**
     * 校验指定章节当前可见（章节详情入口校验，不可见/不存在返回 null 由 Service 报错）。
     *
     * @param bookId       书籍ID（同查防跨书取章）
     * @param chapterId    章节ID
     * @param visibilities 可见性范围
     * @return 可见章节信息；不可见或不存在返回 null
     */
    @Select("""
            <script>
            select bc.id as chapterId, bc.article_id as articleId,
                   bc.chapter_no as chapterNo, a.title as title
            from book_chapter bc
            join article a on a.id = bc.article_id
            where bc.book_id = #{bookId}
              and bc.id = #{chapterId}
              and a.status = 2
              and a.del_flag = 0
              and a.visibility in
              <foreach collection="visibilities" item="v" open="(" separator="," close=")">#{v}</foreach>
            limit 1
            </script>
            """)
    BookChapterVO selectVisibleChapter(@Param("bookId") Long bookId,
                                       @Param("chapterId") Long chapterId,
                                       @Param("visibilities") Collection<Integer> visibilities);

    /**
     * 上一章可见章节（chapter_no &lt; 当前序号，取最接近的一章）。
     *
     * @param bookId       书籍ID
     * @param chapterNo    当前章节序号
     * @param visibilities 可见性范围
     * @return 上一章；无（已是第一章或上一章不可见）返回 null
     */
    @Select("""
            <script>
            select bc.id as chapterId, bc.article_id as articleId,
                   bc.chapter_no as chapterNo, a.title as title
            from book_chapter bc
            join article a on a.id = bc.article_id
            where bc.book_id = #{bookId}
              and bc.chapter_no &lt; #{chapterNo}
              and a.status = 2
              and a.del_flag = 0
              and a.visibility in
              <foreach collection="visibilities" item="v" open="(" separator="," close=")">#{v}</foreach>
            order by bc.chapter_no desc
            limit 1
            </script>
            """)
    BookChapterVO selectPrevVisible(@Param("bookId") Long bookId,
                                    @Param("chapterNo") Integer chapterNo,
                                    @Param("visibilities") Collection<Integer> visibilities);

    /**
     * 下一章可见章节（chapter_no &gt; 当前序号，取最接近的一章）。
     *
     * @param bookId       书籍ID
     * @param chapterNo    当前章节序号
     * @param visibilities 可见性范围
     * @return 下一章；无（已是最新章或下一章不可见）返回 null
     */
    @Select("""
            <script>
            select bc.id as chapterId, bc.article_id as articleId,
                   bc.chapter_no as chapterNo, a.title as title
            from book_chapter bc
            join article a on a.id = bc.article_id
            where bc.book_id = #{bookId}
              and bc.chapter_no &gt; #{chapterNo}
              and a.status = 2
              and a.del_flag = 0
              and a.visibility in
              <foreach collection="visibilities" item="v" open="(" separator="," close=")">#{v}</foreach>
            order by bc.chapter_no asc
            limit 1
            </script>
            """)
    BookChapterVO selectNextVisible(@Param("bookId") Long bookId,
                                    @Param("chapterNo") Integer chapterNo,
                                    @Param("visibilities") Collection<Integer> visibilities);

    /**
     * 书内是否存在「已发布 + 未删 + 仅关注者可见(visibility=3)」的章节。
     *
     * <p>用途：登录用户进书时决定是否回查 FollowRelationCache——书内没有关注者章节
     * 就不用打 social（匿名/非关注者 visibilities=[1] 已够看全部可见章节，省一次跨服务）。</p>
     *
     * @param bookId 书籍ID
     * @return true=存在关注者章节（登录非关注者需回查关注关系）；false=不存在
     */
    @Select("""
            select exists(
                select 1
                from book_chapter bc
                join article a on a.id = bc.article_id
                where bc.book_id = #{bookId}
                  and a.status = 2
                  and a.del_flag = 0
                  and a.visibility = 3
            )
            """)
    Boolean existsFollowersChapter(@Param("bookId") Long bookId);
}