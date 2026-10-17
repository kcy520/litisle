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
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    private final BookMapper bookMapper;

    private final BookChapterMapper bookChapterMapper;

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
        Book book = new Book()
                .setAuthorId(userId)
                .setTitle(dto.getTitle())
                .setIntro(dto.getIntro())
                .setCoverFileId(dto.getCoverFileId())
                .setCategory(dto.getCategory() == null || dto.getCategory().isBlank()
                        ? DEFAULT_CATEGORY : dto.getCategory())
                .setStatus(BookStatusEnum.SERIALING)
                .setDelFlag(DelFlag.NOT_DELETED);
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
        // owner 校验：非本人拒绝（软删书按不存在处理，不暴露存在性）
        Book book = requireOwnedBook(id, userId);
        // 非 null 字段更新（BookSaveDTO 全字段可空，null=不修改）
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
        // owner 校验
        requireOwnedBook(id, userId);
        // 软删书籍（CAS where del_flag=0，并发重复删幂等跳过）；章节挂载关系随书籍失效，物理清挂载行
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
        // 书籍归属校验
        requireOwnedBook(bookId, userId);
        // 文章归属本人校验（防挂他人文章）：须为本人已发布未软删文章
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
        // uk(book_id, chapter_no) 冲突预检（序号即排序键不重复）
        Long conflict = bookChapterMapper.selectCount(new LambdaQueryWrapper<BookChapter>()
                .eq(BookChapter::getBookId, bookId)
                .eq(BookChapter::getChapterNo, dto.getChapterNo()));
        if (conflict > 0) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_NO_CONFLICT);
        }
        // 落挂载行；并发双挂同序号由 DB uk 兑底转冲突码
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
        // 书籍归属校验
        requireOwnedBook(bookId, userId);
        // 章节归属校验：chapter_id 不归属 book_id 拒绝伪造跨书章节
        BookChapter chapter = bookChapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getBookId().equals(bookId)) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_MISMATCH);
        }
        // 仅移除挂载关系不删文章
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
        Book book = bookMapper.selectById(id);
        if (book == null || book.getDelFlag() == DelFlag.DELETED) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        if (!book.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return book;
    }
}