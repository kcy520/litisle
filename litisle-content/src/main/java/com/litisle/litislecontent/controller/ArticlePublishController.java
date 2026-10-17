package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litisleapi.dto.content.ContentPreviewCheckDTO;
import com.litisle.litisleapi.dto.content.ContentPreviewCheckVO;
import com.litisle.litislecontent.domain.dto.ArticlePasswordVerifyDTO;
import com.litisle.litislecontent.domain.vo.ArticleBatchResultVO;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.service.ArticlePublishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.litisle.litislecontent.constant.ContentLimitConstant.*;

/**
 * REST 接口层——发布编排域（与 ArticleController 分文件：CRUD 管内容本体，本类管状态流转）：
 *
 * | 方法 | 路径                        | 说明                                  | 权限 |
 * |------|----------------------------|---------------------------------------|------|
 * | POST | /article/publish-batch     | 批量发布（≤20，逐篇回执）             | 登录 |
 * | POST | /article/batch-visibility  | 批量改可见性（逐篇 CAS + 逐篇发事件） | owner |
 * | POST | /preview-check             | 发布前预检（仅 {hit,risk}）           | 登录·限频 |
 * | POST | /article/{id}/verify-password | 密码访问校验（BCrypt+双层防爆破）  | 登录·限频 |
 *
 * 【发布同步段（口径B）】A1 校验 → A2 免审直落 → A3 CAS 0→1 发事件即返回「审核中」，
 * 不同步等审；终审结论由 moderation.result 事件驱动回写
 */
@Tag(name = "内容服务-发布编排", description = "批量发布/批量可见性/发布前预检")
@RestController
@RequestMapping("/api/content")
@RequiredArgsConstructor
public class ArticlePublishController {

    private final ArticlePublishService publishService;

    /**
     * 批量发布（单批 ≤20，逐篇独立事务 + 逐篇回执，部分失败可定位重提）
     */
    @Operation(summary = "批量发布", description = "登录；单批 ≤ publish.batch-max(20)；已发布篇目幂等跳过；限频 3/60s")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = BATCH_PUBLISH_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PostMapping("/article/publish-batch")
    public Result<List<ArticleBatchResultVO>> publishBatch(@RequestBody List<Long> articleIds) {
        // Service 内逐篇独立事务，失败不回滚已成功篇目
        List<ArticleBatchResultVO> batchResults = publishService.publishBatch(articleIds, StpUtil.getLoginIdAsLong());
        return Result.success(batchResults);
    }

    /**
     * 批量改可见性（owner；逐篇 CAS + 逐篇发 content.unpublished/published）
     */
    @Operation(summary = "批量改可见性", description = "owner；转公开不重审（content_hash 未变原结论仍有效）")
    @SaCheckLogin
    @PostMapping("/article/batch-visibility")
    public Result<Void> batchVisibility(@RequestBody List<Long> articleIds,
                                        @RequestParam("visibility") VisibilityEnum visibility) {
        // 每篇独立发事件，删除/转私密必发 unpublished（隐私红线）；Spring 按枚举 name 绑定
        publishService.batchVisibility(articleIds, visibility, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /**
     * 密码访问校验（[4.2]）：BCrypt 比对不回显；双层防爆破——@RateLimiter 5/60s（固定窗口）
     * + LoginRetryLimiter 连续 5 次失败锁 60s（对齐 auth 登录防爆破口径，超限抛 10005）；
     * 通过后写短时效凭证标记（TTL 10min），getDetail PASSWORD 分支命中放行正文
     */
    @Operation(summary = "密码访问校验", description = "登录；限频 5/60s 防爆破；通过后 10min 窗口内详情正文放行；密码不回显不落日志")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = VERIFY_PASSWORD_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PostMapping("/article/{id}/verify-password")
    public Result<Void> verifyPassword(@PathVariable("id") Long id,
                                       @RequestBody ArticlePasswordVerifyDTO dto) {
        // Service 内完成：锁定闸口 → BCrypt 比对 → 失败计数/成功重置 + 凭证标记写入
        publishService.verifyPassword(id, dto.getPassword(), StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /**
     * 发布前预检（仅 {hit, risk} 不回吐命中词；不替代发布闸门）
     */
    @Operation(summary = "发布前预检", description = "登录；限频 10/60s；仅返回命中与否与风险级，不回吐命中词")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = PREVIEW_CHECK_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PostMapping("/preview-check")
    public Result<ContentPreviewCheckVO> previewCheck(@RequestBody ContentPreviewCheckDTO dto) {
        ContentPreviewCheckVO checkResult = publishService.previewCheck(dto, StpUtil.getLoginIdAsLong());
        return Result.success(checkResult);
    }
}