package com.litisle.litislesearch.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litislesearch.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索服务 Feign 提供方（批次四 4.1，实现 litisle-api 的 SearchClient#search 契约）。
 *
 * 【路径】POST /api/feign/search/search（/api/feign 前缀仅服务间可用，网关未注册路由）。
 * 【说明】同一套参数校验/缓存/兜底逻辑由 {@link SearchService} 收口，
 * 本控制器只负责把 Feign 契约请求转成服务调用，保持 REST 与 Feign 行为一致。
 *
 * @author litisle
 */
@RestController
@RequestMapping("/api/feign/search")
@RequiredArgsConstructor
public class SearchFeignProviderController {

    /** 检索服务（REST 与 Feign 共用同一业务入口） */
    private final SearchService searchService;

    /**
     * 服务间检索（SearchClient#search 契约实现）。
     *
     * @param request 检索请求体（关键词 + 类型/排序/分页/筛选）
     * @return 检索结果（source 标记来源，非 ES 来源调用方不得缓存）
     */
    @PostMapping("/search")
    public Result<SearchResultVO> search(@RequestBody SearchRequestDTO request) {
        return Result.success(searchService.search(request));
    }
}
