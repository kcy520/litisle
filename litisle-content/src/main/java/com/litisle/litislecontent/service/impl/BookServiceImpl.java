package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislecontent.domain.dto.BookSaveDTO;
import com.litisle.litislecontent.domain.dto.ChapterAddDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.Book;
import com.litisle.litislecontent.domain.entity.BookChapter;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.BookStatusEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.BookChapterMapper;
import com.litisle.litislecontent.mapper.BookMapper;
import com.litisle.litislecontent.service.BookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 书籍/合集域实现（owner 管理 + 章节归属校验）。
 *
 * 作用：
 * 提供书籍（合集）的创建/编辑/软删与章节挂载管理。所有写操作先校验书籍归属本人
 * （软删书按不存在处理）；章节挂载要求文章为本人已发布未软删，且章节序号在书内
 * 唯一（先预检查重 + 数据库唯一索引兜底）；删除书籍仅软删书籍并清挂载关系，文章本体不受影响。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    /** 书籍 Mapper（书籍 CRUD 数据源） */
    private final BookMapper bookMapper;

    /** 章节（挂载关系）Mapper（章节挂载/冲突预检/移除数据源） */
    private final BookChapterMapper bookChapterMapper;

    /** 文章 Mapper（章节挂载时的文章归属/状态校验数据源） */
    private final ArticleMapper articleMapper;

    /** 分类缺省值（BookSaveDTO.category 可空，落库默认「小说」） */
    private static final String DEFAULT_CATEGORY = "小说";

    /**
     * 创建书籍/合集（§5.1：默认 status=0 连载中）
     *
     * @param dto    书籍入参（书名/简介/封面等）
     * @param userId 当前登录用户ID（作者归属）
     * @return 新建书籍ID
     */
    @Override
    public Long create(BookSaveDTO dto, Long userId) {

        /**
         * 创建书籍流程：
         *
         * ① 组装实体：归属作者本人；分类没填默认「小说」；状态默认「连载中」；未软删。
         * ② 落库：插入数据库拿到书籍ID返回。
         */

        // 步骤1：组装实体（归属作者；分类缺省「小说」；状态默认连载中；未软删）
        Book book = new Book()
                .setAuthorId(userId)
                .setTitle(dto.getTitle())
                .setIntro(dto.getIntro())
                .setCoverFileId(dto.getCoverFileId())
                .setCategory(dto.getCategory() == null || dto.getCategory().isBlank()
                        ? DEFAULT_CATEGORY : dto.getCategory())
                .setStatus(BookStatusEnum.SERIALING)
                .setDelFlag(DelFlag.NOT_DELETED);
        // 步骤2：落库并返回主键
        bookMapper.insert(book);
        return book.getId();
    }

    /**
     * 编辑书籍（owner 校验，非本人拒绝）
     *
     * @param id     书籍ID
     * @param dto    编辑入参（非 null 字段生效）
     * @param userId 当前登录用户ID
     */
    @Override
    public void update(Long id, BookSaveDTO dto, Long userId) {

        /**
         * 编辑书籍流程：
         *
         * ① 归属校验：仅书籍作者本人可编辑（软删书按不存在处理，不暴露存在性）。
         * ② 增量更新：传了哪个字段就改哪个（null=不修改），组装后统一提交。
         */

        // 步骤1：owner 校验：非本人拒绝（软删书按不存在处理，不暴露存在性）
        Book book = requireOwnedBook(id, userId);
        // 步骤2：非 null 字段更新（BookSaveDTO 全字段可空，null=不修改）
        Book upd = new Book();
        upd.setId(id);
        if (dto.getTitle() != null) {
            upd.setTitle(dto.getTitle());
        }
        if (dto.getIntro() != null) {
            upd.setIntro(dto.getIntro());
        }
        if (dto.getCoverFileId() != null) {
            upd.setCoverFileId(dto.getCoverFileId());
        }
        if (dto.getCategory() != null) {
            upd.setCategory(dto.getCategory());
        }
        bookMapper.updateById(upd);
    }

    /**
     * 删除书籍（owner 校验 + 软删，章节挂载关系随书籍失效）
     *
     * @param id     书籍ID
     * @param userId 当前登录用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId) {

        /**
         * 删除书籍流程：
         *
         * ① 归属校验：仅书籍作者本人可删。
         * ② 软删书籍：带条件改删除标记（并发重复删安全）；同一事务内物理清空章节挂载行。
         *    注意：文章本体不受影响，只是从书里“摘出来”；
         *    指向已移除章节的阅读进度由查询侧按最新章节容错。
         */

        // 步骤1：owner 校验
        requireOwnedBook(id, userId);
        // 步骤2：软删书籍（只改"未删除"状态的行，并发重复删安全）；章节挂载关系随书籍失效，物理清挂载行
        // （挂载关系无独立业务价值，文章本体不动）；阅读进度指向已移除章节由查询侧按最新章容错
        bookMapper.update(null, new LambdaUpdateWrapper<Book>()
                .eq(Book::getId, id)
                .eq(Book::getDelFlag, DelFlag.NOT_DELETED)
                .set(Book::getDelFlag, DelFlag.DELETED));
        bookChapterMapper.delete(new LambdaQueryWrapper<BookChapter>()
                .eq(BookChapter::getBookId, id));
    }

    /**
     * 添加章节（§5.1：文章归属本人校验 + uk(book_id, chapter_no) 冲突拒绝）
     *
     * @param bookId  书籍ID
     * @param dto     章节入参（文章ID/章节序号等）
     * @param userId  当前登录用户ID
     */
    @Override
    public void addChapter(Long bookId, ChapterAddDTO dto, Long userId) {

        /**
         * 添加章节流程：
         *
         * ① 书籍归属校验：只有书的作者能挂章节。
         * ② 文章校验：必须是本人、已发布、未删除的文章（防挂他人文章/未过审内容）。
         * ③ 章节序号预检：同一本书内序号不能重复（序号即阅读顺序）。
         * ④ 落挂载行：并发下两人同时挂同一序号时，由数据库唯一索引兜底转冲突报错。
         */

        // 步骤1：书籍归属校验
        requireOwnedBook(bookId, userId);
        // 步骤2：文章归属本人校验（防挂他人文章）：须为本人已发布未软删文章
        Article article = articleMapper.selectById(dto.getArticleId());
        if (article == null || article.getDelFlag() == DelFlag.DELETED) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        if (!article.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        if (article.getStatus() != ArticleStatusEnum.PUBLISHED) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 步骤3：uk(book_id, chapter_no) 冲突预检（序号即排序键不重复）
        Long conflict = bookChapterMapper.selectCount(new LambdaQueryWrapper<BookChapter>()
                .eq(BookChapter::getBookId, bookId)
                .eq(BookChapter::getChapterNo, dto.getChapterNo()));
        if (conflict > 0) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_NO_CONFLICT);
        }
        // 步骤4：落挂载行；并发下两人同时挂同一序号，由数据库唯一索引兜底转冲突报错
        BookChapter chapter = new BookChapter()
                .setBookId(bookId)
                .setArticleId(dto.getArticleId())
                .setChapterNo(dto.getChapterNo());
        try {
            bookChapterMapper.insert(chapter);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_NO_CONFLICT);
        }
    }

    /**
     * 移除章节（owner 校验，仅移除挂载关系不删文章）
     *
     * @param bookId    书籍ID
     * @param chapterId 章节ID
     * @param userId    当前登录用户ID
     */
    @Override
    public void removeChapter(Long bookId, Long chapterId, Long userId) {

        /**
         * 移除章节流程：
         *
         * ① 书籍归属校验：只有书的作者能移除章节。
         * ② 章节归属校验：该章节必须真的挂在这本书下（防伪造跨书章节ID）。
         * ③ 移除：只删“书-文章”挂载关系，文章本身不删。
         */

        // 步骤1：书籍归属校验
        requireOwnedBook(bookId, userId);
        // 步骤2：章节归属校验：chapter_id 不归属 book_id 拒绝伪造跨书章节
        BookChapter chapter = bookChapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getBookId().equals(bookId)) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_MISMATCH);
        }
        // 步骤3：仅移除挂载关系不删文章
        bookChapterMapper.deleteById(chapterId);
    }

    /**
     * 归属校验复用：书籍存在（未软删）且属本人，否则按码拒绝
     *
     * @param id     书籍ID
     * @param userId 当前登录用户ID
     * @return 已校验书籍实体
     */
    private Book requireOwnedBook(Long id, Long userId) {
        // 步骤1：查书籍（软删书按不存在处理，不暴露存在性）
        Book book = bookMapper.selectById(id);
        if (book == null || book.getDelFlag() == DelFlag.DELETED) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        // 步骤2：归属校验（非本人拒绝）
        if (!book.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return book;
    }
}