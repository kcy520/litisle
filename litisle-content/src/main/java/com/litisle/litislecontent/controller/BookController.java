package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.litislecontent.domain.dto.BookSaveDTO;
import com.litisle.litislecontent.domain.dto.ChapterAddDTO;
import com.litisle.litislecontent.service.BookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * REST 接口层——书籍/合集域：
 *
 * | 方法            | 路径                        | 说明                     | 权限 |
 * |-----------------|----------------------------|--------------------------|------|
 * | POST            | /book                      | 创建书籍（默认连载中）   | 登录 |
 * | PUT             | /book/{id}                 | 编辑书籍                 | owner |
 * | DELETE          | /book/{id}                 | 软删书籍                 | owner |
 * | POST            | /book/{id}/chapter         | 挂载章节（文章归属校验） | owner |
 * | DELETE          | /book/{id}/chapter/{cid}   | 移除章节                 | owner |
 */
@Tag(name = "内容服务-书籍", description = "书籍/章节管理")
@RestController
@RequestMapping("/api/content/book")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

    /** 创建书籍（默认 status=0 连载中） */
    @Operation(summary = "创建书籍")
    @SaCheckLogin
    @PostMapping
    public Result<Long> create(@RequestBody BookSaveDTO dto) {
        Long bookId = bookService.create(dto, StpUtil.getLoginIdAsLong());
        return Result.success(bookId);
    }

    /** 编辑书籍（owner） */
    @Operation(summary = "编辑书籍")
    @SaCheckLogin
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable("id") Long id, @RequestBody BookSaveDTO dto) {
        bookService.update(id, dto, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /** 软删书籍（owner） */
    @Operation(summary = "删除书籍")
    @SaCheckLogin
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        bookService.delete(id, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /** 挂载章节（校验文章归属本人；uk(book_id, chapter_no) 冲突拒绝） */
    @Operation(summary = "挂载章节", description = "章节序号即排序键；非法归属抛 BOOK_CHAPTER_MISMATCH")
    @SaCheckLogin
    @PostMapping("/{id}/chapter")
    public Result<Void> addChapter(@PathVariable("id") Long id, @RequestBody ChapterAddDTO dto) {
        // 路径 bookId 为准（防跨书挂载）
        bookService.addChapter(id, dto, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /** 移除章节（owner） */
    @Operation(summary = "移除章节")
    @SaCheckLogin
    @DeleteMapping("/{id}/chapter/{chapterId}")
    public Result<Void> removeChapter(@PathVariable("id") Long id, @PathVariable("chapterId") Long chapterId) {
        bookService.removeChapter(id, chapterId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}