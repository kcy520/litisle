package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislecontent.domain.dto.ReadingHistoryQueryDTO;
import com.litisle.litislecontent.domain.vo.ReadingHistoryItemVO;
import com.litisle.litislecontent.domain.vo.ReadingHistoryVO;
import com.litisle.litislecontent.service.ReadingHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

import static com.litisle.litislecontent.constant.ContentLimitConstant.*;

/**
 * REST 接口层——阅读历史域：
 *
 * | 方法 | 路径                       | 说明                                      | 权限 |
 * |------|---------------------------|-------------------------------------------|------|
 * | PUT  | /reading-history          | 历史上报（仅前进不回退）                  | 登录·限频 |
 * | GET  | /reading-history          | 阅读历史列表（书籍+文章混合时间线）       | 登录 |
 * | GET  | /reading-history/{bookId} | 续读查询（上次读到第N章）                 | 登录 |
 *
 * 路径说明：列表与上报同路径靠 HTTP 方法区分；列表（无子路径）与 /{bookId} 层级不同不冲突。
 * 章节阅读落历史走阶段17 章节详情端点（recordBookRead，显式 book 上下文），不在本控制器。
 */
@Tag(name = "内容服务-阅读历史", description = "历史上报/阅读历史列表/续读")
@RestController
@RequestMapping("/api/content/reading-history")
@RequiredArgsConstructor
public class ReadingHistoryController {

    private final ReadingHistoryService readingHistoryService;

    /**
     * 历史上报（书籍阅读页显式入口；服务端限频兜底 + 前端 30s 节流；伪造跨书章节拒绝）
     *
     * <p>durationMs 为批次十一 11.11 预留的阅读时长心跳入参：本次只占位不写入、不统计，
     * 等前端心跳口径（间隔/有效判定）确定后再接入；不要在此处凭空定义有效时长规则。</p>
     */
    @Operation(summary = "历史上报", description = "登录；限频 2/60s；仅前进不回退")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = HISTORY_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PutMapping
    public Result<Void> report(@RequestParam("bookId") Long bookId,
                               @RequestParam("chapterId") Long chapterId,
                               @RequestParam(value = "durationMs", required = false) Long durationMs) {
        // Service 内章节归属反查 + UPSERT
        // TODO 11.11：durationMs 待前端心跳方案确定后接入聚合，本次显式忽略
        readingHistoryService.report(StpUtil.getLoginIdAsLong(), bookId, chapterId);
        return Result.success();
    }

    /**
     * 我的阅读历史列表（update_time 倒序；对象/章节信息已装配；空历史返回空页）
     */
    @Operation(summary = "阅读历史列表", description = "登录；bizType null-全部 1-书籍 2-独立文章")
    @SaCheckLogin
    @GetMapping
    public Result<Page<ReadingHistoryItemVO>> myHistory(ReadingHistoryQueryDTO query) {
        // bizType 白名单校验在 Service 层（非法值 PARAM_ERROR）
        return Result.success(readingHistoryService.pageMyHistory(
                StpUtil.getLoginIdAsLong(), query.getBizType(),
                query.getPageNo(), query.getPageSize()));
    }

    /**
     * 续读查询（无历史/章节行已删/章节文章已删返回 null，前端按无续读处理）
     */
    @Operation(summary = "续读查询", description = "返回上次阅读详情（章节ID/序号/标题/最后阅读时间）")
    @SaCheckLogin
    @GetMapping("/{bookId}")
    public Result<ReadingHistoryVO> last(@PathVariable("bookId") Long bookId) {
        // 可见性由阶段17 调用方按书籍可见性 scope 复核（本端点仅本人查自己历史）
        return Result.success(readingHistoryService.getLastRead(StpUtil.getLoginIdAsLong(), bookId));
    }
}
