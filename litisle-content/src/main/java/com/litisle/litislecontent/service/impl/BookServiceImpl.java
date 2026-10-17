package com.litisle.litislecontent.service.impl;

import com.litisle.litislecontent.domain.dto.BookSaveDTO;
import com.litisle.litislecontent.domain.dto.ChapterAddDTO;
import com.litisle.litislecontent.service.BookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 书籍/合集域实现（owner 管理 + 章节归属校验）。
 */
@Service
@Slf4j
public class BookServiceImpl implements BookService {

    /**
     * 创建书籍/合集（§5.1：默认 status=0 连载中）
     *
     * @param dto    书籍入参（书名/简介/封面等）
     * @param userId 当前登录用户ID（作者归属）
     * @return 新建书籍ID
     */
    @Override
    public Long create(BookSaveDTO dto, Long userId) {
        // TODO 默认 status=0 连载中
        return null;
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
        // TODO owner 校验
    }

    /**
     * 删除书籍（owner 校验 + 软删，章节挂载关系随书籍失效）
     *
     * @param id     书籍ID
     * @param userId 当前登录用户ID
     */
    @Override
    public void delete(Long id, Long userId) {
        // TODO 软删
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
        // TODO 文章归属本人校验 + uk(book_id, chapter_no) 冲突拒绝
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
        // TODO owner 校验
    }
}