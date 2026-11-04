package com.litisle.litislesearch.controller;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litisleapi.vo.search.SuggestVO;
import com.litisle.litislesearch.service.SearchService;
import com.litisle.litislesearch.service.SuggestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 站内检索 REST 接口（批次四 4.1，路径前缀 /api/search，网关匿名白名单放行）。
 *
 * | 方法 | 路径                 | 说明                                      | 权限 |
 * |------|---------------------|-------------------------------------------|------|
 * | GET  | /api/search          | 全文检索（文章/书籍/用户，支持筛选排序分页） | 匿名 |
 * | GET  | /api/search/suggest  | 输入联想（书名/作者 completion + 标签池）   | 匿名 |
 *
 * 【说明】参数校验、索引路由、缓存与兜底全部由 {@link SearchService} 收口，
 * 控制器只做契约转换（Result 包装），不承载业务逻辑。
 *
 * @author litisle
 */
@Tag(name = "搜索服务-检索", description = "站内全文检索（文章/书籍/用户）")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    /** 检索服务（唯一业务入口，REST 与 Feign 共用） */
    private final SearchService searchService;

    /** 联想服务（批次六 6.1：书名/作者 completion + content 标签池聚合） */
    private final SuggestService suggestService;

    /**
     * 站内全文检索（匿名可访问）。
     *
     * @param request 检索请求（query 参数绑定：q/type/sort/pageNum/pageSize/tag/category/zone/时间范围）
     * @return 检索结果（按 type 仅对应命中列表非空）
     */
    @Operation(summary = "站内检索", description = "q 必填；type=article/book/user；sort=related/newest/heat；分页受服务端上限约束")
    @GetMapping
    public Result<SearchResultVO> search(SearchRequestDTO request) {
        return Result.success(searchService.search(request));
    }

    /**
     * 输入联想（匿名可访问，批次六 6.1）。
     *
     * <p>聚合书名/作者 completion 联想与 content 标签池联想，按文本去重后返回；
     * 词为空/超长返回空列表，单源故障只丢该源结果。</p>
     *
     * @param q    用户输入前缀（必填，服务端做空值/长度校验）
     * @param size 期望返回条数（可选，越界取配置默认值并封顶）
     * @return 联想列表（type=KEYWORD/AUTHOR/TAG）
     */
    @Operation(summary = "输入联想", description = "q 为输入前缀；size 可选；返回书名/作者/标签联想，按文本去重")
    @GetMapping("/suggest")
    public Result<List<SuggestVO>> suggest(
            @Parameter(description = "输入前缀", required = true)
            @RequestParam("q") String q,
            @Parameter(description = "返回条数（可选）")
            @RequestParam(value = "size", required = false) Integer size) {
        return Result.success(suggestService.suggest(q, size));
    }
}
