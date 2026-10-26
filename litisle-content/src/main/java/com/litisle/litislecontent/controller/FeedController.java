package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislecontent.domain.vo.FeedItemVO;
import com.litisle.litislecontent.service.FeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.litisle.litislecontent.constant.ContentLimitConstant.*;

/**
 * REST 接口层——推荐流域：
 *
 * | 方法 | 路径  | 说明                                   | 权限 |
 * |------|------|----------------------------------------|------|
 * | GET  | /feed | 推荐流（缓存三防 + 展示前回查剔除）    | 登录 |
 */
@Tag(name = "内容服务-推荐流", description = "推荐流分页（预计算推荐链路，zone 1/2 聚合视图）")
@RestController
@RequestMapping("/api/content/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    /**
     * 推荐流分页（缓存 TTL/分页大小外置 yml；渲染前回查防下架残留曝光）
     */
    @Operation(summary = "推荐流", description = "登录；限频 60/60s；已下架/转私密文章即时剔除")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = FEED_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @GetMapping
    public Result<List<FeedItemVO>> feed(@RequestParam(defaultValue = "1") int pageNo,
                                         @RequestParam(defaultValue = "20") int pageSize) {
        List<FeedItemVO> feedItems = feedService.feed(StpUtil.getLoginIdAsLong(), pageNo, pageSize);
        return Result.success(feedItems);
    }
}