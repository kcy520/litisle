package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislecontent.service.ReadingProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

import static com.litisle.litislecontent.constant.ContentLimitConstant.*;

/**
 * REST 接口层——阅读进度域：
 *
 * | 方法 | 路径                        | 说明                              | 权限 |
 * |------|----------------------------|-----------------------------------|------|
 * | PUT  | /reading-progress          | 进度上报（仅前进不回退）          | 登录·限频 |
 * | GET  | /reading-progress/{bookId} | 续读查询                          | 登录 |
 */
@Tag(name = "内容服务-阅读进度", description = "进度上报/续读")
@RestController
@RequestMapping("/api/content/reading-progress")
@RequiredArgsConstructor
public class ReadingProgressController {

    private final ReadingProgressService progressService;

    /**
     * 进度上报（服务端限频兜底 + 前端 30s 节流；伪造跨书章节拒绝）
     */
    @Operation(summary = "进度上报", description = "登录；限频 2/60s；仅前进不回退")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = PROGRESS_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PutMapping
    public Result<Void> report(@RequestParam("bookId") Long bookId,
                               @RequestParam("chapterId") Long chapterId) {
        // Service 内章节归属反查 + UPSERT
        progressService.report(StpUtil.getLoginIdAsLong(), bookId, chapterId);
        return Result.success();
    }

    /**
     * 续读查询（无进度返回 null）
     */
    @Operation(summary = "续读查询", description = "返回最后阅读章节ID")
    @SaCheckLogin
    @GetMapping("/{bookId}")
    public Result<Long> last(@PathVariable("bookId") Long bookId) {
        Long lastChapterId = progressService.getLastChapter(StpUtil.getLoginIdAsLong(), bookId);
        return Result.success(lastChapterId);
    }
}