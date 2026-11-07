package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.domain.dto.FeedFeedbackDTO;
import com.litisle.litislecontent.domain.dto.FeedQuery;
import com.litisle.litislecontent.domain.vo.BookFeedItemVO;
import com.litisle.litislecontent.domain.vo.FeedItemVO;
import com.litisle.litislecontent.domain.vo.FeedPageVO;
import com.litisle.litislecontent.enums.ZoneEnum;
import com.litisle.litislecontent.service.FeedService;
import com.litisle.litislecontent.service.FeedFeedbackService;
import com.litisle.litislecontent.service.FeedStreamService;
import com.litisle.litislecontent.support.FeedRateLimitSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
 * | GET  | /feed/articles | 文章流（游标无限刷，个性化）    | 登录 + 匿名 |
 * | GET  | /feed/books    | 书籍流（游标无限刷，个性化）    | 登录 + 匿名 |
 * | POST | /feed/feedback | 提交负反馈（内容/作者/分类）     | 登录 |
 * | DELETE | /feed/feedback | 撤销负反馈（幂等）            | 登录 |
 */
@Tag(name = "内容服务-推荐流", description = "推荐流分页（预计算推荐链路，zone 1/2 聚合视图）")
@RestController
@RequestMapping("/api/content/feed")
@RequiredArgsConstructor
public class FeedController {

    /** 旧分页式推荐流（保留并存，不下线；批次十不改其语义） */
    private final FeedService feedService;

    /** 新无限刷流（文章流/书籍流独立，游标式供给） */
    private final FeedStreamService feedStreamService;

    /** 推荐流频控（登录按 USER 60/60s、匿名按 IP 20/60s，运行时按身份切换维度） */
    private final FeedRateLimitSupport feedRateLimitSupport;

    /** 负反馈服务（批次十一 11.11：不感兴趣/屏蔽作者/屏蔽分类，必须登录） */
    private final FeedFeedbackService feedFeedbackService;

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

    /**
     * 文章流（批次十 10.2）：登录与匿名均可刷，游标式持续供给
     * <p>匿名标识由 AnonIdFilter 校验/兜底；频控按登录态在 USER/IP 两个维度间切换</p>
     *
     * @param cursor     下一页游标（首次请求为空）
     * @param snapshotId 快照版本号（首次请求为空；回传后用于判断快照是否失效）
     * @param pageSize   单页条数（空则取配置 feed.page-size，超上限按常量截断）
     * @param zoneCode   分区筛选（1-普通 / 2-作家；空/非法值表示不筛选）
     * @param request    HTTP 请求（读取过滤器写入的匿名标识）
     * @return 文章流分页结果（含 cursor/snapshotId/hasMore/reset）
     */
    @Operation(summary = "文章流（无限刷）", description = "登录+匿名；登录 60/60s、匿名 20/60s；游标分页，快照失效返回 reset=true")
    @GetMapping("/articles")
    public Result<FeedPageVO<FeedItemVO>> feedArticles(@RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) String snapshotId,
                                                       @RequestParam(required = false) Integer pageSize,
                                                       @RequestParam(required = false) Integer zoneCode,
                                                       HttpServletRequest request) {
        // 登录用户ID：未登录为 null，后续按匿名口径处理
        Long userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        // 匿名标识：登录用户不使用；匿名时取过滤器写入的请求属性
        String anonId = userId == null ? (String) request.getAttribute(FeedConstant.ANON_ID_ATTRIBUTE) : null;

        // 频控校验（登录 60/60s、匿名 20/60s，超限直接抛业务异常）
        feedRateLimitSupport.check(userId, request);

        // 组装查询入参（分区码转枚举，非法值按不筛选处理）
        FeedQuery query = new FeedQuery()
                .setUserId(userId)
                .setAnonId(anonId)
                .setCursor(cursor)
                .setSnapshotId(snapshotId)
                .setPageSize(pageSize)
                .setZone(toZone(zoneCode));

        return Result.success(feedStreamService.feedArticles(query));
    }

    /**
     * 书籍流（批次十 10.2）：登录与匿名均可刷，不参与 zone，按 category 组织
     *
     * @param cursor     下一页游标（首次请求为空）
     * @param snapshotId 快照版本号（首次请求为空）
     * @param pageSize   单页条数（空则取配置，超上限截断）
     * @param request    HTTP 请求（读取过滤器写入的匿名标识）
     * @return 书籍流分页结果
     */
    @Operation(summary = "书籍流（无限刷）", description = "登录+匿名；书籍不参与 zone；游标分页，下架/私密书籍永不进流")
    @GetMapping("/books")
    public Result<FeedPageVO<BookFeedItemVO>> feedBooks(@RequestParam(required = false) String cursor,
                                                        @RequestParam(required = false) String snapshotId,
                                                        @RequestParam(required = false) Integer pageSize,
                                                        HttpServletRequest request) {
        // 登录用户ID：未登录为 null
        Long userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        // 匿名标识：登录用户不使用
        String anonId = userId == null ? (String) request.getAttribute(FeedConstant.ANON_ID_ATTRIBUTE) : null;

        // 频控校验（文章流与书籍流共用同一配额）
        feedRateLimitSupport.check(userId, request);

        // 组装查询入参（书籍流不传 zone）
        FeedQuery query = new FeedQuery()
                .setUserId(userId)
                .setAnonId(anonId)
                .setCursor(cursor)
                .setSnapshotId(snapshotId)
                .setPageSize(pageSize);

        return Result.success(feedStreamService.feedBooks(query));
    }

    /**
     * 提交负反馈（批次十一 11.11）：不感兴趣（单条内容）、屏蔽作者、屏蔽分类。
     *
     * <p>必须登录：匿名没有稳定身份，无法做长期屏蔽（登录用户按 userId 维度生效）。
     * 提交幂等：重复提交不会产生重复行。频控 20/60s（登录用户维度）。</p>
     *
     * @param dto 反馈入参（类型/流/目标）
     * @return 空结果（成功即返回）
     */
    @Operation(summary = "提交负反馈", description = "登录；不感兴趣/屏蔽作者/屏蔽分类；幂等；20/60s")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = FEEDBACK_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PostMapping("/feedback")
    public Result<Void> submitFeedback(@RequestBody FeedFeedbackDTO dto) {
        feedFeedbackService.submit(StpUtil.getLoginIdAsLong(), dto);
        return Result.success();
    }

    /**
     * 撤销负反馈（批次十一 11.11）：按相同类型/流/目标删除，本来不存在也按成功处理。
     *
     * <p>之所以提供撤销：误点或改变主意很常见，撤销成本仅一条 DELETE；
     * 是否长期生效由 30 天屏蔽时间窗控制，撤销后下一次刷新生效。</p>
     *
     * @param dto 反馈入参（类型/流/目标）
     * @return 空结果（成功即返回）
     */
    @Operation(summary = "撤销负反馈", description = "登录；按目标删除；幂等；20/60s")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = FEEDBACK_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @DeleteMapping("/feedback")
    public Result<Void> cancelFeedback(@RequestBody FeedFeedbackDTO dto) {
        feedFeedbackService.cancel(StpUtil.getLoginIdAsLong(), dto);
        return Result.success();
    }

    /**
     * 分区码转枚举（前端传 1/2；null 或未知值返回 null 表示不筛选）
     *
     * @param zoneCode 分区码
     * @return 分区枚举；非法值为 null
     */
    private static ZoneEnum toZone(Integer zoneCode) {
        if (zoneCode == null) {
            return null;
        }
        // 遍历全部枚举按入库值匹配（枚举仅两个，成本可忽略）
        for (ZoneEnum zone : ZoneEnum.values()) {
            if (zone.getCode() == zoneCode) {
                return zone;
            }
        }
        return null;
    }
}