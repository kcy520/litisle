package com.litisle.litislecontent.service;


import com.litisle.commoncore.result.PageResult;
import com.litisle.litislecontent.domain.dto.BookMyQueryDTO;
import com.litisle.litislecontent.domain.dto.BookQueryDTO;
import com.litisle.litislecontent.domain.dto.BookSaveDTO;
import com.litisle.litislecontent.domain.dto.ChapterAddDTO;
import com.litisle.litislecontent.domain.vo.BookCardVO;
import com.litisle.litislecontent.domain.vo.BookChapterDetailVO;
import com.litisle.litislecontent.domain.vo.BookChapterVO;
import com.litisle.litislecontent.domain.vo.BookDetailVO;

/**
 * 书籍/合集域服务（书籍 CRUD + 章节组织 + 章节阅读编排 + 阅读历史联动容错），REST 由 BookController 调用：
 *
 * Long create(BookSaveDTO, Long userId)                    // 创建书籍（默认连载中）
 * void update(Long id, BookSaveDTO, Long userId)           // 编辑（仅 owner）
 * void delete(Long id, Long userId)                        // 软删
 * void addChapter(Long bookId, ChapterAddDTO, Long userId) // 挂载章节：文章归属本人校验 + uk(book_id, chapter_no) 序号防重
 * void removeChapter(Long bookId, Long chapterId, Long userId) // 移除章节
 * BookDetailVO getDetail(Long bookId, Long viewerId)       // 书籍详情（可选登录）：可见章节数/首尾章/上次阅读/收藏状态
 * PageResult<BookChapterVO> pageChapters(bookId, viewerId, pageNo, pageSize) // 章节列表（可选登录，chapter_no 升序分页）
 * BookChapterDetailVO getChapterDetail(bookId, chapterId, viewerId) // 章节详情（可选登录）：正文复用文章判定链 + 相邻章导航
 * PageResult<BookCardVO> listPublic(BookQueryDTO)          // 公开书籍列表（匿名）：status!=下架 && 未软删，分页+category 过滤
 * PageResult<BookCardVO> listMy(BookMyQueryDTO, Long userId) // 我的书籍（登录）：author_id 硬收口，含下架书，未软删
 *
 * 【职责边界】阅读历史归 ReadingHistoryService（独立域，report/getLastChapter/pageMyHistory 等），
 * 本接口管书籍/章节本体与章节阅读编排（详情/列表/章节详情的可见性计算与组装），避免两域重复签名
 *
 * @author litisle
 */
public interface BookService {

    /**
     * 创建书籍（status 默认 0-连载中）
     *
     * @param dto   书籍入参（书名/简介/封面/分类）
     * @param userId 当前登录用户ID
     * @return 书籍ID
     */
    Long create(BookSaveDTO dto, Long userId);

    /**
     * 编辑书籍（仅 owner）
     *
     * @param id     书籍ID
     * @param dto    编辑入参
     * @param userId 当前登录用户ID
     */
    void update(Long id, BookSaveDTO dto, Long userId);

    /**
     * 软删书籍（仅 owner）
     *
     * @param id     书籍ID
     * @param userId 当前登录用户ID
     */
    void delete(Long id, Long userId);

    /**
     * 挂载章节：文章归属本人校验（防挂他人文章）+ chapter_no 唯一冲突拒绝（序号即排序键）
     *
     * @param bookId 书籍ID
     * @param dto    章节入参（articleId + chapterNo）
     * @param userId 当前登录用户ID
     */
    void addChapter(Long bookId, ChapterAddDTO dto, Long userId);

    /**
     * 移除章节（仅 owner）
     *
     * @param bookId    书籍ID
     * @param chapterId 章节ID
     * @param userId    当前登录用户ID
     */
    void removeChapter(Long bookId, Long chapterId, Long userId);

    /**
     * 书籍详情（可选登录，阶段17）
     * <p>可见章节数 + 首章/最新章定位 + 上次阅读信息（登录且章节当前可见才回填）+ 收藏状态；
     * 匿名可读公开书籍</p>
     *
     * @param bookId   书籍ID
     * @param viewerId 查看者ID（null=匿名）
     * @return 书籍详情（不含全量目录，章节走 pageChapters 分页）
     * @throws com.litisle.commoncore.exception.BizException BOOK_NOT_FOUND 不存在/已删/下架且非作者
     */
    BookDetailVO getDetail(Long bookId, Long viewerId);

    /**
     * 章节列表分页（可选登录，阶段17）
     * <p>chapter_no 升序，SQL 层过滤「已发布+未删+查看者可见」，空页正常返回</p>
     *
     * @param bookId   书籍ID
     * @param viewerId 查看者ID（null=匿名）
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数（章节选择页业务默认 50，上限 PageConstant.MAX_PAGE_SIZE）
     * @return 章节列表项分页
     * @throws com.litisle.commoncore.exception.BizException BOOK_NOT_FOUND 不存在/已删/下架且非作者
     */
    PageResult<BookChapterVO> pageChapters(Long bookId, Long viewerId, long pageNo, long pageSize);

    /**
     * 章节详情（可选登录，阶段17）
     * <p>正文复用文章可见性判定链（不可见抛 ARTICLE_NOT_FOUND）；
     * 相邻章节（prev/next）随详情一次返回，前端上一章/下一章直接用，不再查目录</p>
     *
     * @param bookId    书籍ID
     * @param chapterId 章节ID
     * @param viewerId  查看者ID（null=匿名）
     * @return 章节详情（书籍上下文 + 章节信息 + 文章正文 + 相邻章导航）
     * @throws com.litisle.commoncore.exception.BizException
     *         BOOK_NOT_FOUND 书籍不可读 / BOOK_CHAPTER_MISMATCH 章节不归属该书 /
     *         ARTICLE_NOT_FOUND 章节文章不可见
     */
    BookChapterDetailVO getChapterDetail(Long bookId, Long chapterId, Long viewerId);

    /**
     * 公开书籍列表（匿名，P2 书籍发现入口）
     * <p>硬条件：status != 下架（连载中/已完结）&& del_flag=未删除；
     * category 可选精确过滤；排序走 PageQuery（默认 update_time 倒序，匿名仅通用时间列白名单）</p>
     *
     * @param query 查询条件（分页 + category 过滤）
     * @return 书籍卡片分页
     */
    PageResult<BookCardVO> listPublic(BookQueryDTO query);

    /**
     * 我的书籍分页（登录，P2 书籍发现入口）
     * <p>硬条件：author_id=当前登录人（不信任前端传参）&& del_flag=未删除（软删书不展示）；
     * 含下架书（作者侧自查）；category/status/keyword 可选过滤；
     * 排序走 PageQuery sortBy 时间列白名单（登录端点开放，白名单外回退默认）</p>
     *
     * @param query  查询条件（分页 + category/status/keyword）
     * @param userId 当前登录用户ID（归属硬条件）
     * @return 书籍卡片分页
     */
    PageResult<BookCardVO> listMy(BookMyQueryDTO query, Long userId);
}
