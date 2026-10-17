package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.enums.AuditModule;
import com.litisle.commoncore.enums.AuditOperation;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.commonsecurity.audit.AuditLog;
import com.litisle.litislecontent.domain.dto.ArticlePublishDTO;
import com.litisle.litislecontent.domain.dto.ArticleUpdateDTO;
import com.litisle.litislecontent.domain.vo.ArticleCardVO;
import com.litisle.litislecontent.domain.vo.ArticleDetailVO;
import com.litisle.litislecontent.service.ArticleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.litisle.litislecontent.constant.ContentLimitConstant.*;

/**
 * REST 接口层（路径前缀 /api/content，Knife4j 已扫本包）——文章 CRUD 域：
 *
 * | 方法   | 路径            | 说明                                   | 权限            |
 * |--------|----------------|----------------------------------------|-----------------|
 * | POST   | /article       | 创建/发布（zone=2 需 AUTHOR）            | 登录            |
 * | PUT    | /article/{id}  | 编辑（status=0/3 全字段；2 仅元数据 方案B） | owner           |
 * | POST   | /article/{id}/submit | 驳回后修改重提（CAS 3→1）          | owner           |
 * | DELETE | /article/{id}  | 软删 + 必发 content.unpublished        | owner/ADMIN     |
 * | GET    | /article/{id}  | 详情（visibility 判定链收口）           | 按 visibility（匿名可见公开文） |
 * | GET    | /article/list  | 公开列表（不带正文大字段）               | 匿名            |
 * | GET    | /article/hot   | 热门文章                                 | 匿名            |
 *
 * 要点：
 * - GET 三个端点为网关白名单匿名路径，拦截器已方法感知放行，不加 @SaCheckLogin；
 *   详情的私密性由 Service visibility 判定链收口（非本人按不存在处理）
 * - ownerId 统一 StpUtil.getLoginIdAsLong() 取当前登录人（全项目现行口径，对齐 file 模块）
 */
@Tag(name = "内容服务-文章", description = "文章 CRUD/详情/公开列表")
@RestController
@RequestMapping("/api/content/article")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    /**
     * 创建/发布（zone=2 需 AUTHOR；发布状态流转由 ArticlePublishService 编排）
     */
    @Operation(summary = "创建/发布文章", description = "登录；zone=2 需 AUTHOR 角色；限频 10/60s")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = PUBLISH_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PostMapping
    public Result<Long> create(@RequestBody ArticlePublishDTO dto) {
        // Service 内做 zone 权限校验 + 内容校验 + 状态流转
        Long articleId = articleService.createAndPublish(dto, StpUtil.getLoginIdAsLong());
        return Result.success(articleId);
    }

    /**
     * 编辑（owner；status=2 仅元数据可改——方案B，改正文走软删重发）
     */
    @Operation(summary = "编辑文章", description = "owner；status=2 仅封面/摘要/分类/visibility 等元数据可改")
    @SaCheckLogin
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable("id") Long id, @RequestBody ArticleUpdateDTO dto) {
        // 路径 id 为准（防错位改写他人文章）
        articleService.update(id, dto, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /**
     * 驳回后修改重提（CAS 3→1，afterCommit 发 content.submitted）
     */
    @Operation(summary = "驳回重提", description = "owner；CAS 3→1；限频 10/60s")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = PUBLISH_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PostMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable("id") Long id) {
        articleService.submit(id, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /**
     * 软删（owner/ADMIN；必发 content.unpublished——隐私红线）
     * <p>@AuditLog 审计留痕（操作人/时间/articleId 切面自动收集；ADMIN 治理动作与作者软删均记录，
     * 责任归属由 operatorId 区分）；请求体无敏感参数，saveRequestData=false 双保险</p>
     */
    @Operation(summary = "删除文章", description = "owner/ADMIN；软删并发 content.unpublished 防索引残留")
    @AuditLog(moudle = AuditModule.CONTENT, operation = AuditOperation.ARTICLE_DELETE,
            saveRequestData = false)
    @SaCheckLogin
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        articleService.delete(id, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /**
     * 详情（匿名可见公开文；visibility 判定链在 Service 收口）
     */
    @Operation(summary = "文章详情", description = "匿名仅可见公开文；仅自己/关注者/密码可见性由 Service 判定链收口")
    @GetMapping("/{id}")
    public Result<ArticleDetailVO> detail(@PathVariable("id") Long id) {
        // 未登录（匿名）viewerId 传 null；登录取当前人
        Long viewerId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        ArticleDetailVO detail = articleService.getDetail(id, viewerId);
        return Result.success(detail);
    }

    /**
     * 公开列表（匿名；仅 status=2 且 visibility=1；不带 content_html）
     */
    @Operation(summary = "公开文章列表", description = "匿名可访问；大字段延迟加载，列表不含正文")
    @GetMapping("/list")
    public Result<List<ArticleCardVO>> list(@RequestParam(required = false) Integer zone,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(defaultValue = "1") int pageNo,
                                            @RequestParam(defaultValue = "10") int pageSize) {
        List<ArticleCardVO> cards = articleService.listPublic(zone, category, pageNo, pageSize);
        return Result.success(cards);
    }

    /**
     * 热门文章（匿名；按 view_cnt 倒序 + 缓存三防）
     */
    @Operation(summary = "热门文章", description = "匿名可访问；view_cnt 冗余计数排序，异步刷新")
    @GetMapping("/hot")
    public Result<List<ArticleCardVO>> hot(@RequestParam(defaultValue = "10") int pageSize) {
        List<ArticleCardVO> hotList = articleService.listHot(pageSize);
        return Result.success(hotList);
    }
}