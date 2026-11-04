package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.dto.ArticleImageDTO;
import com.litisle.litislecontent.domain.dto.ArticleMyQueryDTO;
import com.litisle.litislecontent.domain.dto.ArticlePublicQueryDTO;
import com.litisle.litislecontent.domain.dto.ArticlePublishDTO;
import com.litisle.litislecontent.domain.dto.ArticleSaveDTO;
import com.litisle.litislecontent.domain.dto.ArticleUpdateDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.domain.router.ZoneRouter;
import com.litisle.litislecontent.domain.statemachine.ArticleStateMachine;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.cache.UnpublishedCacheCleaner;
import com.litisle.litislecontent.service.ArticleService;
import com.litisle.litislecontent.service.support.ArticleEventSupport;
import com.litisle.litislecontent.service.support.ArticleImageRefSupport;
import com.litisle.litislecontent.service.support.ArticleImageSupport;
import com.litisle.litislecontent.service.support.ArticlePublishSupport;
import com.litisle.litislecontent.service.support.ArticleQuerySupport;
import com.litisle.litislecontent.utils.ArticleHtmlSanitizer;
import com.litisle.litislecontent.utils.ContentLengthGuard;
import com.litisle.litislecontent.utils.LogSanitizeUtil;
import com.litisle.commoncore.result.PageResult;
import com.litisle.litislecontent.domain.vo.ArticleCardVO;
import com.litisle.litislecontent.domain.vo.ArticleDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文章域门面实现（薄委托：接口转发 + update/submit/delete 方法体 + requireArticle）。
 *
 * 作用：
 * 只承担 ArticleService 接口的请求转发，以及 update/submit/delete 三个写方法体的编排；
 * 业务实现已按域拆分至 service/support/ 四个支撑组件（2026-11-03 拆分，行为与拆分前完全一致）：
 *   - ArticlePublishSupport（发布写链路域）：createAndPublish/saveDraftRow 方法体 + 摘要 DFA 闸门
 *     （checkSummarySensitiveWords 同时供本类 update 改摘要时复用）
 *   - ArticleImageSupport（图片归属域）：插图归属校验/配图挂载/草稿→文章归属切换/封面引用交接/
 *     配图聚合指纹/配图实体转 VO（本类 update 配图整体重挂注入复用前两项）
 *   - ArticleEventSupport（事务后事件域）：content.submitted/image.submit/published/unpublished/
 *     article-updated 五类事件的 afterCommit 注册（本类 update/submit/delete 注入复用）
 *   - ArticleQuerySupport（读链路域）：listMy/listPublic/listHot/getDetail + toCardVO
 *     （含批次E D-3 热榜逻辑与 FeedHotRankSupport/ContentProperties 依赖，整体随迁）
 * 【可见性判定链（§6.4）】getDetail 按 visibility 收口（阶段9：密码访问 4 已下线）：
 *   1-公开放行；2-仅自己（owner 校验，非本人按不存在处理，不暴露存在性）；
 *   3-仅关注者（回查 social 关注关系，降级=按非关注者处理——宁严勿松，结果加短 TTL 缓存±10%抖动）
 * 【软删红线】deleteById 必发 content.unpublished（四路径统一收口之一，防向量库/索引残留）
 * 【大字段延迟加载】列表查询不带 content_html，详情页单独取
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    /** 文章 Mapper（编辑条件更新/重提 CAS/软删/requireArticle 数据源） */
    private final ArticleMapper articleMapper;

    /** 文章配图 Mapper（编辑配图整体重挂查删旧行 + 软删查全量配图数据源） */
    private final ArticleImageMapper articleImageMapper;

    /** 文章/草稿插图引用支撑组件（编辑封面校验登记/重挂差集解绑 + 软删配图封面解绑统一收口） */
    private final ArticleImageRefSupport articleImageRefSupport;

    /** 文章状态机（送审/重提流转白名单预校验） */
    private final ArticleStateMachine articleStateMachine;

    /** 分区路由权限校验（[3.3] 单点收口：编辑分区变更统一走本组件） */
    private final ZoneRouter zoneRouter;

    /** 下架审核缓存失效编排（[3.4] 方案 A：last-hash 精确删 + text/img-result SCAN 通配版本段） */
    private final UnpublishedCacheCleaner unpublishedCacheCleaner;

    /** 发布写链路域支撑组件（createAndPublish/saveDraftRow 委托目标；摘要 DFA 闸门供 update 复用） */
    private final ArticlePublishSupport articlePublishSupport;

    /** 图片归属域支撑组件（update 配图整体重挂：插图归属校验 + 配图挂载复用） */
    private final ArticleImageSupport articleImageSupport;

    /** 事务后事件域支撑组件（update/submit/delete 的事务提交后事件注册复用） */
    private final ArticleEventSupport articleEventSupport;

    /** 读链路域支撑组件（listMy/getDetail/listPublic/listHot 委托目标） */
    private final ArticleQuerySupport articleQuerySupport;

    /**
     * 创建并发布：薄委托 ArticlePublishSupport#createAndPublish 执行（发布编排全流程见该类）。
     * 事务语义与拆分前一致：门面声明 @Transactional，委托调用经 Support 代理加入同一事务
     *
     * @param dto      发布入参（标题/正文/配图/分区/可见性等）
     * @param authorId 作者用户ID（插图归属校验基准）
     * @return 新建文章ID
     * @throws BizException CONTENT_EMPTY 图文同空 / ARTICLE_PARAM_INVALID 参数非法 / ARTICLE_IMG_REFUSED 插图越权
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAndPublish(ArticlePublishDTO dto, Long authorId) {
        // 一行委托：发布编排（快照保稿/分区权限/图文校验/清洗/敏感词/指纹落库/配图接线/免审判定/送审事件）
        return articlePublishSupport.createAndPublish(dto, authorId);
    }

    /**
     * 文章建档（不发布）：薄委托 ArticlePublishSupport#saveDraftRow 执行（建档流程见该类）。
     * 落库 status=0 草稿态行、不发任何 MQ 事件，供 publishBatch 批量送审链路使用
     *
     * @param dto      建档入参
     * @param authorId 作者用户ID（插图归属校验基准）
     * @return 新建文章ID（status=0）
     * @throws BizException CONTENT_EMPTY 图文同空 / ARTICLE_PARAM_INVALID 参数非法 / SENSITIVE_WORD_DETECTED 命中黑名单词
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraftRow(ArticleSaveDTO dto, Long authorId) {
        // 一行委托：建档编排（与发布同加工链路，差异仅在落库状态与不发事件）
        return articlePublishSupport.saveDraftRow(dto, authorId);
    }

    /**
     * 作者侧"我的文章"分页列表：薄委托 ArticleQuerySupport#listMy 执行
     * （author_id=本人硬条件 + status/published 二选一过滤，详见该类）
     *
     * @param query    查询条件（status/published 二选一/zone/category/keyword 可选；分页参数经 PageQuery 归一化）
     * @param authorId 当前登录作者ID
     * @return 分页卡片列表（不带正文大字段）
     */
    @Override
    public PageResult<ArticleCardVO> listMy(ArticleMyQueryDTO query, Long authorId) {
        // 一行委托：读链路（我的文章分页）
        return articleQuerySupport.listMy(query, authorId);
    }

    /**
     * 编辑文章：归属（本人或 ADMIN）→ 方案B（已发布的文章传正文域字段即拒，防借编辑绕过复审）→
     * 分区权限校验 → 非 null 字段清洗后增量更新 → 转私密在事务提交后发 content.unpublished
     *
     * 已发布的文章（status=2）禁止修改正文内容，只能改可见性/分区等元数据；
     * 未发布的文章允许正常编辑，所有传入字段经 sanitize 后增量更新。
     *
     * 编辑=元数据随便改（任意状态），正文域（标题/正文/配图）只在草稿/驳回态可改，已发布改正文的唯一出路是软删重发。
     *
     * @param id         文章ID
     * @param dto        编辑入参（非 null 字段生效；配图 images 非 null 整体重挂）
     * @param operatorId 操作人ID（归属/权限校验基准）
     * @throws BizException ARTICLE_NOT_FOUND 不存在 / NO_PERMISSION 越权 / ARTICLE_PARAM_INVALID 方案B 拒改或密码缺失
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ArticleUpdateDTO dto, Long operatorId) {

        /**
         * 文章编辑流程：
         *
         * ① 归属与权限检查
         *    只有作者本人或 ADMIN 可以编辑，其他人直接拒绝（防横向越权）。
         *
         * ② 已发布正文域禁改（方案B）
         *    已发布的文章传标题/正文/配图即拒绝——改正文必须软删重发，
         *    防止借“编辑”绕过机审复审；可见性/分区等元数据不受限。
         *
         * ③ 分区变更权限
         *    换分区时按新分区门槛重新校验（与发布同款规则）。
         *
         * ④ 增量清洗更新
         *    非 null 字段才生效；标题/摘要剥标签，正文白名单净化后更新。
         *
         * ⑤ 封面变更处理
         *    新封面先归属校验再绑定引用；旧封面在事务提交后解绑。
         *
         * ⑥ 配图整体重挂
         *    用前端传来的列表完全替换 DB 里的图片：删旧行 → 挂新行 → 绑定新引用，
         *    差集（旧图−新图）在事务提交后逐个解绑。
         *
         * ⑦ 可见性变更联动
         *    转公开：CAS 改状态，事务提交后通知搜索/AI 重新收录（不重审）；
         *    转私密：先失效审核缓存，事务提交后发下架事件（防隐私残留）。
         */

        Article article = requireArticle(id);
        // 归属校验：非本人且非 ADMIN 拒绝（横向越权防护）
        boolean owner = article.getAuthorId().equals(operatorId);
        if (!owner && !SaRoleHelper.isAdmin()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        boolean published = article.getStatus() == ArticleStatusEnum.PUBLISHED;
        // 方案B：status=2 正文域字段（标题/正文/配图）传入即拒——改正文须软删重发，防借编辑绕过复审
        if (published && (dto.getTitle() != null || dto.getContentHtml() != null || dto.getImages() != null)) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 分区变更沿用发布同款权限（ZoneRouter 单点收口 [3.3]）
        if (dto.getZone() != null) {
            zoneRouter.checkPublishPermission(dto.getZone());
        }
        // 定时发布入参校验（项2）：设置/取消是否请求 + 互斥与状态门槛（仅 status=0 草稿态允许操作）
        boolean wantSetSchedule = dto.getScheduledPublishTime() != null;
        boolean wantCancelSchedule = Boolean.TRUE.equals(dto.getCancelScheduledPublish());
        if ((wantSetSchedule || wantCancelSchedule) && article.getStatus() != ArticleStatusEnum.DRAFT) {
            // 仅草稿态可设置/取消定时：待审/已发布/驳回/下架传参直接拒绝，不做静默丢弃
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        if (wantSetSchedule && wantCancelSchedule) {
            // 设置定时与取消定时互斥：同传无法区分作者意图，直接拒绝
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        if (wantSetSchedule) {
            // @Future 的 Service 侧实拦（Controller 未启 @Valid，DTO 注解仅作声明性契约）
            if (!dto.getScheduledPublishTime().isAfter(java.time.LocalDateTime.now())) {
                throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID, "定时发布时间必须晚于当前时间");
            }
        }
        Article upd = new Article().setId(id);
        if (dto.getTitle() != null) {
            upd.setTitle(ArticleHtmlSanitizer.stripTags(dto.getTitle()));
        }
        if (dto.getSummary() != null) {
            // Q1 摘要同步闸门：已发布态摘要放行可改，但改前必须过 DFA（黑名单拦截——改摘要唯一出路口径=扫描通过即可改）
            String summary = ArticleHtmlSanitizer.stripTags(dto.getSummary());
            articlePublishSupport.checkSummarySensitiveWords(summary, article.getAuthorId());
            upd.setSummary(summary);
        }
        if (dto.getContentHtml() != null) {
            // 正文长度硬上限（防滥用兜底，与发布/建档同上限）：编辑也是写正文路径，防借编辑灌入超大正文
            ContentLengthGuard.checkContentLength(dto.getContentHtml());
            upd.setContentHtml(ArticleHtmlSanitizer.sanitize(dto.getContentHtml()));
        }
        upd.setCoverFileId(dto.getCoverFileId());
        // 阶段8② 封面引用闭环（编辑链路）：非空且变更才处理（null=不修改封面，MP not_null 更新策略沿用）
        if (dto.getCoverFileId() != null && !dto.getCoverFileId().equals(article.getCoverFileId())) {
            // 归属校验（防挂他人文件当封面）+ 同一事务内登记新封面引用（有问题立刻报错）
            articleImageRefSupport.checkCoverOwnership(dto.getCoverFileId(), article.getAuthorId());
            //事务内单文件登记引用
            articleImageRefSupport.bindSingleRef(dto.getCoverFileId(),
                    ContentBizConstant.FILE_BIZ_TYPE_COVER, id, article.getAuthorId());
            // 事务提交后按 业务类型+文章ID+用户 精确匹配解绑旧封面引用（失败只记 warn 日志，留待对账兑底）
            if (article.getCoverFileId() != null) {
                articleImageRefSupport.unbindRefsAfterCommit(
                        List.of(article.getCoverFileId()),
                        ContentBizConstant.FILE_BIZ_TYPE_COVER, id, article.getAuthorId());
            }
        }
        upd.setCategory(dto.getCategory());
        upd.setZone(dto.getZone());
        boolean toPrivate = false;
        boolean toPublic = false;
        if (dto.getVisibility() != null) {
            // 转公开（visibility 2/3→1 且 status=2，阶段9：原 4 已下线）不需要重审（[4.1]：方案B 禁改正文 + 内容指纹没变，
            // 原审核结论仍然有效）；单独用条件更新改状态，不放进上面的通用更新集合（status 保持不变，不与下架终态流转混淆）
            toPublic = published && dto.getVisibility() == VisibilityEnum.PUBLIC
                    && article.getVisibility() != VisibilityEnum.PUBLIC;
            if (!toPublic) {
                upd.setVisibility(dto.getVisibility());
                // 转非公开（已发布文 → PRIVATE）隐私红线：必发 content.unpublished
                toPrivate = published && dto.getVisibility() == VisibilityEnum.PRIVATE
                        && article.getVisibility() != VisibilityEnum.PRIVATE;
            }
        }
        // 配图整体重挂（仅 status=0/3；null=不修改）
            ///让 DB 里的图片列表 = 前端传来的列表。
        //删除原图片
        if (dto.getImages() != null) {
            articleImageSupport.checkImageOwnership(dto.getImages(), article.getAuthorId());
            // 阶段6：删前先查旧挂图 fileId（差集解绑基准，删后即无从比对）
            List<String> oldFileIds = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                            .eq(ArticleImage::getArticleId, id))
                    .stream().map(ArticleImage::getFileId).collect(Collectors.toList());
            articleImageMapper.delete(new LambdaQueryWrapper<ArticleImage>()
                    .eq(ArticleImage::getArticleId, id));

            // 重挂新图 + 同一事务内逐张登记引用（有问题立刻报错，不会出现"图挂上了但引用没登记"的中间状态）
            articleImageSupport.saveArticleImages(dto.getImages(), id, null, article.getAuthorId());

            // 事务提交后把"旧列表减新列表"得到的弃用图逐个解绑（按 业务类型+文章ID+用户 精确匹配；
            // 失败只记 warn 日志不中断：残留引用最多晚删，保守方向不会误删别人的引用）
            List<String> removedFileIds = articleImageRefSupport.diffOldMinusNew(
                    oldFileIds, dto.getImages().stream().map(ArticleImageDTO::getFileId).toList());
            articleImageRefSupport.unbindRefsAfterCommit(removedFileIds,
                    ContentBizConstant.FILE_BIZ_TYPE_ARTICLE_IMG, id, article.getAuthorId());
        }
        //重新更新图片
        articleMapper.updateById(upd);
        if (wantSetSchedule) {
            // 设置/覆盖定时发布时间（单独条件更新，限定草稿态：并发下状态被流转则改不动直接报错；
            // 不放进 upd 通用集合是为对齐 toPublic 先例，避免与 not_null 更新策略耦合）
            int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, id)
                    .eq(Article::getStatus, ArticleStatusEnum.DRAFT)
                    .set(Article::getScheduledPublishTime, dto.getScheduledPublishTime()));
            if (rows == 0) {
                // 条件更新未命中=并发已流转（如刚好被手动发布），按参数错误回滚本次编辑
                throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
            }
        }
        if (wantCancelSchedule) {
            // 取消定时：显式条件更新写 NULL（MP not_null 更新策略下 updateById 传 null 字段会被跳过，写不了 NULL）
            int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, id)
                    .eq(Article::getStatus, ArticleStatusEnum.DRAFT)
                    .set(Article::getScheduledPublishTime, null));
            if (rows == 0) {
                // 条件更新未命中=并发已流转，按参数错误回滚本次编辑
                throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
            }
        }
        if (toPublic) {
            // 转公开带条件更新（要求当前仍是已发布状态）：并发下架/删除时改不动，直接报错
            int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, id)
                    .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                    .set(Article::getVisibility, VisibilityEnum.PUBLIC));
            if (rows == 0) {
                throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
            }
            // afterCommit 发 content.published（search/ai 重新收录；不重建/不重写任何审核缓存，原键仍有效）
            articleEventSupport.publishPublishedAfterCommit(article);
        }
        if (toPrivate) {
            // 下架审核缓存失效（[3.4] 方案 A，事务内同步执行，fail-safe 不阻断主流程）
            unpublishedCacheCleaner.invalidateArticle(article.getId(), article.getContentHash());
            // afterCommit 发下架事件（search/ai 移除索引与向量，防隐私残留）；
            // 转公开不重审（content_hash 校验）随板块四 [4.2] 补 published 事件
            articleEventSupport.publishUnpublishedAfterCommit(article, ContentBizConstant.UNPUBLISH_REASON_TO_PRIVATE);
        }
        // A2 元数据编辑同步（批次B B7 发布点）：已发布文章改了摘要/封面/分类/分区后，
        // 事务提交后发 content.article-updated 让 search 局部刷新索引字段（不动向量与 chunks）；
        // 转公开已发 content.published（全量重收录）、转私密已发 content.unpublished（文档移除），不再叠加本事件
        boolean metaChanged = published && !toPublic && !toPrivate
                && (dto.getSummary() != null                 // 摘要
                || dto.getCategory() != null                 // 分类
                || dto.getZone() != null                     // 分区
                || (dto.getCoverFileId() != null             // 封面（有变更才发）
                && !dto.getCoverFileId().equals(article.getCoverFileId())));
        if (metaChanged) {
            articleEventSupport.publishArticleUpdatedAfterCommit(article);
        }
    }

    /**
     * 驳回修改重提：仅作者本人 → 状态机白名单预校验（禁 2→1）→ 带"当前必须是驳回态"的条件改为待审（并发兑底）
     *即驳回并且修改后，再次送审
     * @param articleId  文章ID
     * @param operatorId 操作人ID（须为作者本人）
     * @throws BizException ARTICLE_NOT_FOUND 不存在 / NO_PERMISSION 非本人 / ARTICLE_PARAM_INVALID 状态机拒绝或条件更新没改成
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long articleId, Long operatorId) {

        /**
         * 驳回修改重提流程：
         *
         * ① 本人校验
         *    仅作者本人可重提，他人直接拒绝。
         *
         * ② 状态机预校验
         *    只有“驳回(3)→待审(1)”在白名单内；已发布(2)的文章不能走这条路（改判只认申诉来源）。
         *
         * ③ CAS 流转
         *    where status=3 抢占式改为“待审核”，并发冲突时直接失败（防重复提交）。
         *
         * ④ 送审通知
         *    事务提交后发 content.submitted，重新进入机审队列。
         */

        Article article = requireArticle(articleId);
        // 驳回修改重提仅限作者本人
        if (!article.getAuthorId().equals(operatorId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // 状态机白名单预校验（3→1 允许；2→1 天然拒绝——改判只认申诉来源）
        if (!articleStateMachine.canTransit(article.getStatus(), ArticleStatusEnum.PENDING_REVIEW)) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // CAS 3→1（并发兜底：where status=3，MP 自动拼 del_flag=0）
        int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getStatus, ArticleStatusEnum.REJECTED)
                .set(Article::getStatus, ArticleStatusEnum.PENDING_REVIEW));
        if (rows == 0) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 驳回重提照常送审：afterCommit 发单条 content.submitted（事件体不含正文，review 回查）
        articleEventSupport.registerSubmitEventAfterCommit(articleId, article.getContentHash());
    }

    /**
     * 软删文章：作者本人或 ADMIN → CAS 软删（del_flag 逻辑删除）→ 操作日志剥控制字符防注入 →
     * afterCommit 必发 content.unpublished（隐私红线：search/ai 移除残留）
     *
     * @param articleId  文章ID
     * @param operatorId 操作人ID
     * @throws BizException ARTICLE_NOT_FOUND 不存在或已删（CAS affected=0） / NO_PERMISSION 越权
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long articleId, Long operatorId) {

        /**
         * 软删流程：
         *
         * ① 归属校验
         *    作者本人或 ADMIN 可删，其他人拒绝。
         *
         * ② 逻辑删除
         *    MP 自动把 DELETE 转 UPDATE del_flag=1；删不到说明文章不存在，直接报错。
         *
         * ③ 引用解绑
         *    查全量配图+封面，事务提交后逐个解绑文件引用（防残留引用锁死秒传共享文件）。
         *
         * ④ 审核缓存失效
         *    精确删 last-hash + 通配清审核结果缓存（防脏免审判定）。
         *
         * ⑤ 操作日志
         *    标题剥控制字符防日志伪造注入。
         *
         * ⑥ 下架事件
         *    事务提交后必发 content.unpublished（隐私红线：搜索索引/AI 向量库同步移除残留）。
         */

        Article article = requireArticle(articleId);
        // 作者本人或 ADMIN 可删
        if (!article.getAuthorId().equals(operatorId) && !SaRoleHelper.isAdmin()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // CAS 软删（MP 逻辑删除：DELETE 转 UPDATE del_flag=1，自动拼 del_flag=0）
        int rows = articleMapper.deleteById(articleId);
        if (rows == 0) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // 阶段6 软删红线：查全量配图（软删前基准），afterCommit 逐图 unbind ARTICLE_IMG 引用
        // （补偿性清理 warn 留痕不阻断：残留引用仅晚删/不删，防锁死秒传共享文件被他人复用）
        //mysql查询配图列表
        List<String> deletedFileIds = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                        .eq(ArticleImage::getArticleId, articleId))
                .stream().map(ArticleImage::getFileId).collect(Collectors.toList());
        //删除配图引用
        articleImageRefSupport.unbindRefsAfterCommit(deletedFileIds,
                ContentBizConstant.FILE_BIZ_TYPE_ARTICLE_IMG, articleId, article.getAuthorId());
        // 阶段8② 软删封面解绑：coverFileId 游离在 article_image 外（无图行可查），按主表字段直取 unbind
        if (article.getCoverFileId() != null) {
            articleImageRefSupport.unbindRefsAfterCommit(
                    List.of(article.getCoverFileId()),
                    ContentBizConstant.FILE_BIZ_TYPE_COVER, articleId, article.getAuthorId());
        }
        // 下架审核缓存失效（[3.4] 方案 A：last-hash 精确删 + text/img-result SCAN，fail-safe 不阻断主流程，
        // 残留键 TTL 兜底；原板块八 TODO 提前在本板块收口）
        unpublishedCacheCleaner.invalidateArticle(articleId, article.getContentHash());
        // 作者侧操作日志：标题为 UGC 自由文本，剥 \r\n 防日志伪造注入（对齐 review §七.1 落日志口径）
        log.info("文章软删，articleId={}，operatorId={}，title={}",
                articleId, operatorId, LogSanitizeUtil.sanitizeForLog(article.getTitle()));
        // 发布下架通知：软删隐私红线：afterCommit 必发 content.unpublished（search/ai 移除残留）
        articleEventSupport.publishUnpublishedAfterCommit(article, ContentBizConstant.UNPUBLISH_REASON_DELETE);
    }

    /**
     * 文章详情：薄委托 ArticleQuerySupport#getDetail 执行
     * （visibility 三级判定链 + 配图占位符渲染 + 收藏回填，详见该类）
     *
     * @param articleId 文章ID
     * @param viewerId  查看者ID（null=匿名，仅可读发布态）
     * @return 详情 VO（正文按可见性判定放行）
     * @throws BizException ARTICLE_NOT_FOUND 不存在/非发布态/可见性拦截
     */
    @Override
    public ArticleDetailVO getDetail(Long articleId, Long viewerId) {
        // 一行委托：读链路（详情可见性判定链）
        return articleQuerySupport.getDetail(articleId, viewerId);
    }

    /**
     * 公开文章列表：薄委托 ArticleQuerySupport#listPublic 执行
     * （匿名可读规则 status=2 且 visibility=1，排序固定 update_time 降序，详见该类）
     *
     * @param query 分页查询条件（zone/category 可空=全部；分页参数经 PageQuery 统一兜底钳制）
     * @return 分页结果（total/totalPages/hasNext 供前端分页组件渲染；卡片 publishTime 取 update_time）
     */
    @Override
    public PageResult<ArticleCardVO> listPublic(ArticlePublicQueryDTO query) {
        // 一行委托：读链路（公开列表）
        return articleQuerySupport.listPublic(query);
    }

    /**
     * 热门文章列表：薄委托 ArticleQuerySupport#listHot 执行
     * （批次E D-3：主路径读 CONTENT_FEED_HOT_ZSET 热度榜，榜空/开关关闭回退 view_cnt 直查，详见该类）
     *
     * @param pageSize 取前 N 条
     * @return 文章卡片列表
     */
    @Override
    public List<ArticleCardVO> listHot(int pageSize) {
        // 一行委托：读链路（热榜，D-3 逻辑整体随迁 QuerySupport）
        return articleQuerySupport.listHot(pageSize);
    }

    // ==================== 私有辅助 ====================

    /**
     * 查文章并判空（del_flag 逻辑删除由 MP 自动过滤，已删即查不到 → 不存在）
     *
     * @param articleId 文章ID
     * @return 文章实体（查无即抛 ARTICLE_NOT_FOUND）
     */
    private Article requireArticle(Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        return article;
    }
}
