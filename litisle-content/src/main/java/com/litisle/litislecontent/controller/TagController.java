package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislecontent.domain.vo.TagVO;
import com.litisle.litislecontent.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.litisle.litislecontent.constant.ContentLimitConstant.*;

/**
 * REST 接口层——标签域：
 *
 * | 方法   | 路径                    | 说明                          | 权限 |
 * |--------|------------------------|-------------------------------|------|
 * | GET    | /tags?keyword=         | 前缀搜索已有标签池（防碎片化）| 登录 |
 * | GET    | /tags/hot              | 热门标签 TOP N                | 登录 |
 * | POST   | /article/{id}/tags     | 挂载标签（≤5，新建过 DFA）    | owner |
 * | DELETE | /article/{id}/tags     | 取消挂载                      | owner |
 */
@Tag(name = "内容服务-标签", description = "标签搜索/热门/挂载")
@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /** 前缀搜索已有标签池（输入联想，防标签碎片化；限频 30/60s 防爬，矩阵外补充） */
    @Operation(summary = "标签搜索", description = "登录；仅返回已有标签池命中项；限频 30/60s")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = TAG_SEARCH_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @GetMapping("/tags")
    public Result<List<TagVO>> search(@RequestParam("keyword") String keyword) {
        List<TagVO> tags = tagService.searchByPrefix(keyword);
        return Result.success(tags);
    }

    /** 热门标签 TOP N（use_count 冗余计数，异步刷新） */
    @Operation(summary = "热门标签", description = "登录；封禁/已删标签不聚合展示")
    @SaCheckLogin
    @GetMapping("/tags/hot")
    public Result<List<TagVO>> hot() {
        List<TagVO> hotTags = tagService.hotTags();
        return Result.success(hotTags);
    }

    /** 挂载标签（≤5 超限抛 ARTICLE_TAG_EXCEEDED；新建过 DFA 闸门） */
    @Operation(summary = "挂载标签", description = "owner；每篇上限 5 个；uk 冲突幂等跳过")
    @SaCheckLogin
    @PostMapping("/article/{id}/tags")
    public Result<Void> attach(@PathVariable("id") Long id, @RequestBody List<String> tagNames) {
        tagService.attachTags(id, tagNames, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /** 取消挂载（owner） */
    @Operation(summary = "取消标签", description = "owner")
    @SaCheckLogin
    @DeleteMapping("/article/{id}/tags")
    public Result<Void> detach(@PathVariable("id") Long id, @RequestBody List<Long> tagIds) {
        tagService.detachTags(id, tagIds, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}