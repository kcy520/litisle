package com.litisle.litislesearch.controller;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import com.litisle.litislesearch.service.SimilarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 相似文章推荐 REST 接口（批次七 7.1，路径前缀 /api/search/similar，网关匿名白名单放行）。
 *
 * | 方法 | 路径                            | 说明                       | 权限 |
 * |------|--------------------------------|----------------------------|------|
 * | GET  | /api/search/similar/{articleId}| KNN 相似推荐（融合评分排序） | 匿名 |
 *
 * 【说明】业务逻辑全部由 {@link SimilarService} 收口，控制器只做参数绑定与 Result 包装。
 *
 * @author litisle
 */
@Tag(name = "搜索服务-相似推荐", description = "基于文章向量的相似文章推荐")
@RestController
@RequestMapping("/api/search/similar")
@RequiredArgsConstructor
public class SimilarController {

    /** 相似文章推荐服务（REST 与 Feign 共用同一业务入口） */
    private final SimilarService similarService;

    /**
     * 查询相似文章（匿名可访问）。
     *
     * @param articleId 源文章 ID
     * @param topK      返回条数（可选，缺省取配置默认值并受上限约束）
     * @return 相似文章列表（按融合分降序）
     */
    @Operation(summary = "相似文章推荐", description = "以源文章向量做 KNN；强制仅公开文章；topK 受服务端上限约束")
    @GetMapping("/{articleId}")
    public Result<List<SimilarArticleVO>> similar(
            @Parameter(description = "源文章 ID", required = true)
            @PathVariable("articleId") Long articleId,
            @Parameter(description = "返回条数（可选）")
            @RequestParam(value = "topK", required = false) Integer topK) {
        return Result.success(similarService.similar(articleId, topK));
    }
}
