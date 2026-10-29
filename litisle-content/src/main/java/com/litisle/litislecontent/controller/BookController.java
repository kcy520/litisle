package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.PageResult;
import com.litisle.commoncore.result.Result;
import com.litisle.litislecontent.domain.dto.BookChapterQueryDTO;
import com.litisle.litislecontent.domain.dto.BookMyQueryDTO;
import com.litisle.litislecontent.domain.dto.BookQueryDTO;
import com.litisle.litislecontent.domain.dto.BookSaveDTO;
import com.litisle.litislecontent.domain.dto.ChapterAddDTO;
import com.litisle.litislecontent.domain.vo.BookCardVO;
import com.litisle.litislecontent.domain.vo.BookChapterDetailVO;
import com.litisle.litislecontent.domain.vo.BookChapterVO;
import com.litisle.litislecontent.domain.vo.BookDetailVO;
import com.litisle.litislecontent.service.BookService;
import com.litisle.litislecontent.service.support.BookReadSupport;
import com.litisle.litislecontent.service.support.ClientIpSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
 * | GET             | /book/{id}                 | 书籍详情（匿名可读/登录带上次阅读与收藏态） | 可选登录 |
 * | GET             | /book/{id}/chapters        | 章节列表分页（chapter_no 升序） | 可选登录 |
 * | GET             | /book/{id}/chapter/{cid}   | 章节详情（正文+相邻章导航；触发浏览计数与书籍历史） | 可选登录 |
 * | GET             | /book/list                 | 公开书籍列表（status!=下架 && 未软删） | 匿名 |
 * | GET             | /book/my                   | 我的书籍（author_id 硬收口，含下架书） | 登录（仅本人） |
 *
 * 越权口径：读端点可选登录（匿名可读公开书籍，登录带个性化字段）；
 * 写端点一律 @SaCheckLogin + Service 层 owner 校验，不因网关白名单通配放宽。
 * 匿名口径：/book/list 与 /book/{id} 同受网关白名单 /api/content/book/* 一层通配覆盖；
 * GET /book/my 为登录端点（@SaCheckLogin，白名单通配不影响注解鉴权）
 */
@Tag(name = "内容服务-书籍", description = "书籍/章节管理与阅读")
@RestController
@RequestMapping("/api/content/book")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;

    /** 章节阅读副作用组件（浏览计数 + 阅读历史，fail-safe 不影响章节响应，阶段17） */
    private final BookReadSupport bookReadSupport;

    /** 客户端 IP 解析组件（章节详情匿名浏览去重基准，与文章详情共用收口，阶段17） */
    private final ClientIpSupport clientIpSupport;

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

    /**
     * 公开书籍列表（匿名，P2 书籍发现入口）。
     * <p>仅连载中/已完结且未软删（下架书不对外，requireReadableBook 同口径）；
     * category 可选精确过滤；排序默认 update_time 倒序（匿名仅通用时间列白名单）；
     * 一期不做 keyword 搜索（search 模块书籍索引为外部依赖）</p>
     */
    @Operation(summary = "公开书籍列表", description = "匿名；status!=下架 && 未软删；category 过滤；分页参数走 PageQuery")
    @GetMapping("/list")
    public Result<PageResult<BookCardVO>> list(BookQueryDTO query) {
        // GET 查询参数自动绑定到 DTO（继承 PageQuery 分页兜底/排序白名单统一收口）；
        // 下架/软删过滤在 Service 硬编码（DTO 类型上不存在状态字段，不可伪造）
        return Result.success(bookService.listPublic(query));
    }

    /**
     * 作者侧"我的书籍"分页列表（登录，P2 书籍发现入口）。
     * <p>author_id=当前登录人硬条件（只看自己的书），含下架书（作者自查）、不含软删书；
     * category/status/keyword 可选过滤；复用文章 my 列表的归属收口模式</p>
     */
    @Operation(summary = "我的书籍列表", description = "登录；author_id=当前登录人硬收口；category/status/keyword 可选过滤；分页参数走 PageQuery")
    @SaCheckLogin
    @GetMapping("/my")
    public Result<PageResult<BookCardVO>> my(BookMyQueryDTO query) {
        // GET 查询参数自动绑定到 DTO；归属条件在 Service 内硬收口（不信任前端）
        return Result.success(bookService.listMy(query, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 书籍详情（可选登录，阶段17）。
     * <p>匿名可读公开书籍；登录时回填上次阅读（章节当前可见才返回）与收藏状态；
     * 全量目录不在此返回，章节走分页接口（大书目录一次取全成本高）</p>
     */
    @Operation(summary = "书籍详情", description = "匿名可读；登录带上次阅读（继续阅读）与收藏状态")
    @GetMapping("/{id}")
    public Result<BookDetailVO> detail(@PathVariable("id") Long id) {
        // 匿名可读：未登录 viewerId 传 null（可见性按匿名口径计算）
        Long viewerId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        return Result.success(bookService.getDetail(id, viewerId));
    }

    /**
     * 章节列表分页（可选登录，阶段17）。
     * <p>chapter_no 升序（排序在 Service 的 SQL 内完成，不走 PageQuery 默认 update_time 排序）；
     * 默认每页 50（BookChapterQueryDTO 构造器设默认），上限 100；
     * 「上次读到」高亮由前端用书籍详情 lastReadChapterId 对比列表 chapterId</p>
     */
    @Operation(summary = "章节列表", description = "匿名可读；chapter_no 升序分页；默认 50/页上限 100")
    @GetMapping("/{id}/chapters")
    public Result<PageResult<BookChapterVO>> chapters(@PathVariable("id") Long id, BookChapterQueryDTO query) {
        // 匿名可读；分页排序由 Service SQL 层收口（chapter_no asc），Controller 只传页参
        Long viewerId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        return Result.success(bookService.pageChapters(id, viewerId, query.getPageNo(), query.getPageSize()));
    }

    /**
     * 章节详情（可选登录，阶段17）。
     * <p>正文复用文章可见性判定链（不可见抛 ARTICLE_NOT_FOUND）；
     * 上一章/下一章随详情一次返回（相邻不可见章节自动跳过）；
     * 成功后触发浏览计数 + 书籍阅读历史（均 fail-safe，不影响章节响应）</p>
     */
    @Operation(summary = "章节详情", description = "匿名可读；正文+相邻章导航；触发浏览计数与书籍历史（登录非作者）")
    @GetMapping("/{id}/chapter/{chapterId}")
    public Result<BookChapterDetailVO> chapterDetail(@PathVariable("id") Long id,
                                                     @PathVariable("chapterId") Long chapterId,
                                                     HttpServletRequest request) {
        // 匿名可读；章节可见性按查看者口径计算
        Long viewerId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        BookChapterDetailVO detail = bookService.getChapterDetail(id, chapterId, viewerId);
        // 阶段17：可见性判定通过后记录阅读副作用（浏览计数按文章维度 + 书籍历史按书维度；fail-safe）
        bookReadSupport.recordChapterRead(id, chapterId, detail.getArticle().getId(),
                detail.getBookAuthorId(), viewerId,
                clientIpSupport.resolveClientIp(request), request.getHeader("User-Agent"));
        return Result.success(detail);
    }
}
