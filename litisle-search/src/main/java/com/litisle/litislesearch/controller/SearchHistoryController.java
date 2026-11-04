package com.litisle.litislesearch.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.PageResult;
import com.litisle.commoncore.result.Result;
import com.litisle.litislesearch.domain.vo.SearchHistoryVO;
import com.litisle.litislesearch.service.SearchHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索历史 REST 接口（批次六 6.2，路径前缀 /api/search/history）。
 *
 * | 方法   | 路径                | 说明                     | 权限 |
 * |--------|---------------------|--------------------------|------|
 * | GET    | /api/search/history | 本人历史分页（时间降序）   | 登录 |
 * | DELETE | /api/search/history | 清空本人全部历史           | 登录 |
 *
 * 【权限口径】网关匿名白名单不含 /api/search/history，服务侧再以当前登录用户 ID 收口，
 * 任何查询/清理都只操作本人数据，不存在 userId 参数（防越权）。
 *
 * @author litisle
 */
@Tag(name = "搜索服务-历史", description = "本人搜索历史查询与清空")
@RestController
@RequestMapping("/api/search/history")
@RequiredArgsConstructor
public class SearchHistoryController {

    /** 搜索历史服务（记录/查询/清空唯一业务入口） */
    private final SearchHistoryService searchHistoryService;

    /**
     * 分页查询本人搜索历史（最近搜索时间降序）。
     *
     * @param pageNum  页码（默认 1）
     * @param pageSize 每页条数（默认 10，服务端有上限）
     * @return 分页历史列表
     */
    @Operation(summary = "查询搜索历史", description = "仅本人可见；按最近搜索时间降序分页")
    @GetMapping
    public Result<PageResult<SearchHistoryVO>> list(
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        return Result.success(searchHistoryService.list(currentUserId(), pageNum, pageSize));
    }

    /**
     * 清空本人全部搜索历史。
     *
     * @return 删除条数
     */
    @Operation(summary = "清空搜索历史", description = "仅清空当前登录用户的历史记录")
    @DeleteMapping
    public Result<Integer> clear() {
        return Result.success(searchHistoryService.clear(currentUserId()));
    }

    /**
     * 取当前登录用户 ID（未登录时抛出未登录异常，由全局异常处理器转标准错误）。
     *
     * @return 当前登录用户 ID
     */
    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}
