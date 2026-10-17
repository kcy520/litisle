package com.litisle.litislecontent.service;


import com.litisle.litislecontent.domain.dto.BookSaveDTO;
import com.litisle.litislecontent.domain.dto.ChapterAddDTO;
import com.litisle.litislecontent.domain.vo.BookDetailVO;

import java.util.List;

/**
 * 书籍/合集域服务（书籍 CRUD + 章节组织 + 阅读进度），REST 由 BookController 调用：
 *
 * Long create(BookSaveDTO, Long userId)                    // 创建书籍（默认连载中）
 * void update(Long id, BookSaveDTO, Long userId)           // 编辑（仅 owner）
 * void delete(Long id, Long userId)                        // 软删
 * void addChapter(Long bookId, ChapterAddDTO, Long userId) // 挂载章节：文章归属本人校验 + uk(book_id, chapter_no) 序号防重
 * void removeChapter(Long bookId, Long chapterId, Long userId) // 移除章节
 *
 * 【职责边界】阅读进度归 ReadingProgressService（独立域，report/getLastChapter），
 * 本接口只管书籍/章节本体，避免两域重复签名
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
}
