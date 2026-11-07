package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.enums.AuditModule;
import com.litisle.commoncore.enums.AuditOperation;
import com.litisle.commoncore.result.PageResult;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.commonsecurity.audit.AuditLog;
import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.domain.dto.ArticlePublishDTO;
import com.litisle.litislecontent.domain.dto.ArticleMyQueryDTO;
import com.litisle.litislecontent.domain.dto.ArticlePublicQueryDTO;
import com.litisle.litislecontent.domain.dto.ArticleSaveDTO;
import com.litisle.litislecontent.domain.dto.ArticleUpdateDTO;
import com.litisle.litislecontent.domain.vo.ArticleCardVO;
import com.litisle.litislecontent.domain.vo.ArticleDetailVO;
import com.litisle.litislecontent.service.ArticleService;
import com.litisle.litislecontent.service.support.ClientIpSupport;
import com.litisle.litislecontent.service.support.FeedExposureSupport;
import com.litisle.litislecontent.service.support.ReadingHistorySupport;
import com.litisle.litislecontent.service.support.ViewCounterSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.litisle.litislecontent.constant.ContentLimitConstant.*;

/**
 * REST 接口层（路径前缀 /api/content，Knife4j 已扫本包）——文章 CRUD 域：
 *
 * | 方法   | 路径            | 说明                                   | 权限            |
 * |--------|----------------|----------------------------------------|-----------------|
 * | POST   | /article       | 创建/发布（zone=2 需 AUTHOR）            | 登录            |
 * | POST   | /article/save  | 建档不发布（status=0；供批量送审）        | 登录            |
 * | PUT    | /article/{id}  | 编辑（status=0/3 全字段；2 仅元数据 方案B） | owner           |
 * | POST   | /article/{id}/submit | 驳回后修改重提（CAS 3→1）          | owner           |
 * | DELETE | /article/{id}  | 软删 + 必发 content.unpublished        | owner/ADMIN     |
 * | GET    | /article/{id}  | 详情（visibility 判定链收口）           | 按 visibility（匿名可见公开文） |
 * | GET    | /article/list  | 公开列表（不带正文大字段）               | 匿名            |
 * | GET    | /article/hot   | 热门文章                                 | 匿名            |
 * | GET    | /article/my    | 作者侧我的文章（含 status=0 建档行筛选）  | 登录（仅本人）   |
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

    /** 浏览计数支撑组件（阶段8①：详情命中后 INCR+去重窗口，fail-safe） */
    private final ViewCounterSupport viewCounterSupport;

    /** 阅读历史支撑组件（阶段15：详情命中后自动落历史，匿名/自读过滤 + fail-safe） */
    private final ReadingHistorySupport readingHistorySupport;

    /** 客户端 IP 解析组件（阶段17：原 resolveClientIp 私有逻辑收口，与章节详情共用） */
    private final ClientIpSupport clientIpSupport;

    /** 曝光/点击归因支撑（批次十一 11.6：详情带 from=feed + snapshotId 时回写点击，失败不影响详情） */
    private final FeedExposureSupport feedExposureSupport;

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
     * 文章建档（不发布）：落库 status=0 草稿态行，不发事件；
     * 供"先建档攒一批 → 批量送审"链路使用，建档行可经编辑接口（status=0 全字段）修改后送审
     */
    @Operation(summary = "文章建档（不发布）", description = "登录；落库草稿态行（status=0），后续 publishBatch 批量送审；限频 10/60s")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = PUBLISH_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PostMapping("/save")
    public Result<Long> saveDraftRow(@RequestBody ArticleSaveDTO dto) {
        // Service 内做 zone 权限校验 + 内容校验 + 落库草稿态（不发事件）
        Long l = articleService.saveDraftRow(dto, StpUtil.getLoginIdAsLong());
        return Result.success(l);
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
     * 详情（匿名可见公开文；visibility 判定链在 Service 收口；阶段8①：命中后记录浏览计数）
     *
     * <p>批次十一 11.6 点击归因：仅当同时携带 {@code from=feed} 与 {@code snapshotId} 时才计推荐流点击；
     * {@code position} 只供前端定位展示，服务端计数不需要，接收后忽略。</p>
     *
     * @param articleId  文章 ID（路径）
     * @param from       归因来源（feed=推荐流；其他值按普通浏览处理）
     * @param snapshotId 快照代次（归因必带参数，仅校验不落库）
     * @param position   卡片位置（可选，仅前端使用）
     * @param request    HTTP 请求（匿名点击时读取 X-Anon-Id）
     * @return 文章详情
     */
    @Operation(summary = "文章详情", description = "匿名仅可见公开文；仅自己/关注者/密码可见性由 Service 判定链收口；from=feed&snapshotId 计推荐点击")
    @GetMapping("/{articleId}")
    public Result<ArticleDetailVO> detail(@PathVariable("articleId") Long articleId,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String snapshotId,
                                          @RequestParam(required = false) Integer position,
                                          HttpServletRequest request) {
        // 未登录（匿名）viewerId 传 null；登录取当前人
        Long viewerId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        ArticleDetailVO detail = articleService.getDetail(articleId, viewerId);
        // 阶段8①：详情命中（可见性判定通过）后记录浏览（PV+去重窗口；fail-safe 不影响响应）
        viewCounterSupport.recordView(articleId, viewerId, clientIpSupport.resolveClientIp(request), request.getHeader("User-Agent"));
        // 阶段15：阅读即落历史——走到此处详情已可见性判定通过（异常/不可见/软删不会到这里）；
        // 匿名/自读过滤、归属判定（独立文章/章节文章/多书歧义）与 UPSERT 收口在 Support/Service；
        // detail 为 null 时防御性跳过；落库异常由 Support fail-safe 吞掉，不阻断详情返回
        if (detail != null) {
            readingHistorySupport.recordArticleHistory(viewerId, articleId, detail.getAuthorId());
            // 批次十一 11.6：可见性判定通过后才计推荐流点击；参数不齐/重复点击/写库失败均由 Support 内部收口
            feedExposureSupport.recordClick(FeedConstant.STREAM_ARTICLE, articleId, viewerId,
                    request.getHeader(FeedConstant.ANON_ID_HEADER), from, snapshotId);
        }
        return Result.success(detail);
    }

    /**
     * 作者侧"我的文章"分页列表（登录；author_id=当前登录人硬条件，只看自己的文章）：
     * 状态三态筛选——status 细粒度单值优先 / published 二值（true=已发布 / false=未发布）/
     * 都不传=全部；传 status=0 可列出建档草稿态行，挑选篇目拿 ID 列表后调批量送审接口
     */
    @Operation(summary = "我的文章列表", description = "登录；author_id=当前登录人；status/published/zone/category/keyword 可选过滤；分页参数走 PageQuery")
    @SaCheckLogin
    @GetMapping("/my")
    public Result<PageResult<ArticleCardVO>> my(ArticleMyQueryDTO query) {
        // GET 查询参数自动绑定到 DTO（继承 PageQuery 分页字段）；归属条件在 Service 内硬收口（不信任前端）
        return Result.success(articleService.listMy(query, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 解析客户端真实 IP 的私有实现已上收至 ClientIpSupport（阶段17），
     * 与章节详情共用同一份 X-Forwarded-For 首段解析，避免两份实现漂移
     */

    /**
     * 公开列表（匿名；仅 status=2 且 visibility=1；不带 content_html）
     * 入参收口 ArticlePublicQueryDTO（仅 zone/category——状态/可见性业务层硬编码，
     * 类型上不存在状态字段；继承 PageQuery：分页兑底/上限钳制/枚举白名单绑定统一收口）
     */
    @Operation(summary = "公开文章列表", description = "匿名可访问；大字段延迟加载，列表不含正文；返回 PageResult 带分页信息")
    @GetMapping("/list")
    public Result<PageResult<ArticleCardVO>> list(ArticlePublicQueryDTO query) {
        return Result.success(articleService.listPublic(query));
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