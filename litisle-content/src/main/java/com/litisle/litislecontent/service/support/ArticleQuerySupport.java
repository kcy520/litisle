package com.litisle.litislecontent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.constant.PageConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.PageResult;
import com.litisle.litislecontent.cache.FollowRelationCache;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.domain.dto.ArticleMyQueryDTO;
import com.litisle.litislecontent.domain.dto.ArticlePublicQueryDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.domain.vo.ArticleCardVO;
import com.litisle.litislecontent.domain.vo.ArticleDetailVO;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.FavoriteTargetTypeEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.service.support.ArticleImageRenderSupport;
import com.litisle.litislecontent.service.support.ArticleImageSupport;
import com.litisle.litislecontent.service.support.FavoriteStateSupport;
import com.litisle.litislecontent.service.support.FeedHotRankSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文章读链路域支撑组件（原 ArticleServiceImpl 读侧私有链路收口）。
 *
 * 作用：
 * 统一收口文章的四个查询入口与卡片 VO 转换，均不触碰写状态、不发 MQ 事件：
 *   ① listMy：作者侧"我的文章"分页列表（author_id=本人硬条件，含 status=0 建档草稿态行）；
 *   ② listPublic：公开列表（匿名可读规则：status=2 且 visibility=1，排序固定 update_time 降序）；
 *   ③ listHot：热门列表（批次E P5 定案 D-3 2026-11-03：主路径读 CONTENT_FEED_HOT_ZSET 热度榜，
 *      榜空或 feed.hot.enabled 关闭时回退 view_cnt 倒序直查）；
 *   ④ getDetail：详情（visibility 三级判定链 §6.4，配图占位符渲染 + 收藏状态回填）；
 *   ⑤ toCardVO：文章实体 → 卡片 VO（publishTime 取 update_time）。
 *
 * 【可见性判定链（§6.4）】getDetail 按 visibility 收口（阶段9：密码访问 4 已下线）：
 *   1-公开放行；2-仅自己（owner 校验，非本人按不存在处理，不暴露存在性）；
 *   3-仅关注者（回查 social 关注关系，降级=按非关注者处理——宁严勿松，结果加短 TTL 缓存±10%抖动）
 * 【大字段延迟加载】列表查询不带 content_html，详情页单独取
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class ArticleQuerySupport {

    /** 文章 Mapper（列表/详情/热榜查询数据源） */
    private final ArticleMapper articleMapper;

    /** 文章配图 Mapper（详情页配图回显反查数据源） */
    private final ArticleImageMapper articleImageMapper;

    /** 正文配图占位符渲染支撑组件（详情回显：fileId 批量换 URL + 占位符替换 <img>） */
    private final ArticleImageRenderSupport articleImageRenderSupport;

    /** 关注关系回查缓存（[4.1] visibility=3：短 TTL 缓存 + miss 回查 SocialClient，fail-fast） */
    private final FollowRelationCache followRelationCache;

    /** 收藏状态查询组件（getDetail 末尾回填 favorited 字段，阶段16） */
    private final FavoriteStateSupport favoriteStateSupport;

    /** 热度榜支撑（P5/D-3 批次E 2026-11-03：/article/hot 切读 CONTENT_FEED_HOT_ZSET 热度榜，与 feed 热榜同源） */
    private final FeedHotRankSupport feedHotRankSupport;

    /** 内容域配置（feed.hot.enabled 开关：榜链路关闭时 /article/hot 回退 view_cnt 直查） */
    private final ContentProperties contentProperties;

    /** 文章配图归属域支撑组件（配图实体 → VO 转换复用） */
    private final ArticleImageSupport articleImageSupport;

    /**
     * 作者侧"我的文章"分页列表（建档→攒批→批量送审闭环的挑选篇目入口）。
     *
     * 作用：
     * 按 author_id=当前登录人硬条件过滤，只返回本人文章；status 可选过滤
     * （含 status=0 建档草稿态行，供批量送审前挑选篇目拿 ID 列表）。
     *
     * @param query    查询条件（status/published 二选一/zone/category/keyword 可选；分页参数经 PageQuery 归一化）
     * @param authorId 当前登录作者ID
     * @return 分页卡片列表（不带正文大字段）
     */
    public PageResult<ArticleCardVO> listMy(ArticleMyQueryDTO query, Long authorId) {

        /**
         * 我的文章列表流程：
         *
         * ① 组装查询条件：author_id=本人（硬条件）；status/zone/category 可空=不过滤；
         *    排除正文大字段（列表不加载 content_html，详情页才取）。
         * ② MyBatis-Plus 分页查询：分页兑底由 PageQuery 收口；排序走 sortBy 白名单
         *    （作者侧登录端点开放白名单内自定义排序，白名单外回退默认降序）。
         * ③ 封装 PageResult 返回（total/totalPages/hasNext 供前端分页组件）。
         */

        // 步骤1：查询条件：本人文章（硬条件）；状态筛选三态：status 单值优先（细粒度）→
        // published 二值（true=已发布 2；false=未发布 0/1/3/4 一把圈住）→ 都不传=全部状态；
        // keyword 标题模糊匹配（作者侧找回文章用，传了才拼）
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .select(Article.class, i -> !Article.COLUMN_CONTENT_HTML.equals(i.getColumn()))
                .eq(Article::getAuthorId, authorId)
                .eq(query.getStatus() != null, Article::getStatus, query.getStatus())
                .eq(query.getStatus() == null && Boolean.TRUE.equals(query.getPublished()),
                        Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .ne(query.getStatus() == null && Boolean.FALSE.equals(query.getPublished()),
                        Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(query.getZone() != null, Article::getZone, query.getZone())
                .eq(query.getCategory() != null, Article::getCategory, query.getCategory())
                .like(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        Article::getTitle, query.getKeyword());
        // 步骤2：分页查询：排序走 PageQuery sortBy 白名单（作者侧登录端点，开放白名单内自定义排序）
        Page<Article> page = articleMapper.selectPage(query.toMpPage(), wrapper);
        // 步骤3：分页信息 + 记录转 VO 一行收敛
        return PageResult.of(page, this::toCardVO);
    }

    /**
     * 文章详情（visibility 三级判定链，§6.4；阶段9：密码访问已下线）：非本人读非发布态按不存在处理不暴露存在性；
     * PRIVATE 非本人拒 / FOLLOWERS 降级宁严勿松；配图占位符渲染回显
     *
     * @param articleId 文章ID
     * @param viewerId  查看者ID（null=匿名，仅可读发布态）
     * @return 详情 VO（正文按可见性判定放行）
     * @throws BizException ARTICLE_NOT_FOUND 不存在/非发布态/可见性拦截
     */
    public ArticleDetailVO getDetail(Long articleId, Long viewerId) {

        /**
         * 详情查询流程（可见性判定链）：
         *
         * ① 查文章
         *    已删/不存在直接按“文章不存在”处理。
         *
         * ② 非发布态拦截
         *    非本人看未发布/驳回/下架的文章，一律按不存在处理，不暴露“这篇文章存在”的事实。
         *
         * ③ 可见性判定（仅非本人时）
         *    - 匿名用户：非公开一律按不存在（省一次关注关系查询）；
         *    - 仅自己(PRIVATE)：非本人按不存在；
         *    - 仅关注者(FOLLOWERS)：回查关注关系（短 TTL 缓存），非关注者按不存在；
         *    - 公开(PUBLIC)：直接放行。
         *
         * ④ 渲染回显
         *    查配图列表 → 批量换 URL（防 N+1）→ 正文占位符替换成带对齐样式的 <img>；
         *    数据不全的占位符原样保留降级，不破坏正文结构。
         */

        Article article = requireArticle(articleId);
        boolean owner = viewerId != null && article.getAuthorId().equals(viewerId);
        // 非本人读非发布态 → 按不存在处理（不暴露草稿/驳回/下架存在性）
        if (!owner && article.getStatus() != ArticleStatusEnum.PUBLISHED) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        VisibilityEnum visibility = article.getVisibility();
        String contentHtml = article.getContentHtml();
        if (!owner) {
            // 匿名（viewerId=null）非公开一律按不存在处理：不回查关注关系（省一次跨服务调用），
            // 也避免 ownerId:null 负缓存键污染
            if (viewerId == null && visibility != VisibilityEnum.PUBLIC) {
                throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
            }
            switch (visibility) {
                case PRIVATE -> {
                    // 仅自己：非本人按不存在，不暴露存在性
                    throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
                }
                case FOLLOWERS -> {
                    // 仅关注者（[4.1]）：回查 social 关注关系（短 TTL 缓存 ±10% 抖动，查不到不放行）；
                    // 非关注者按不存在处理，不暴露存在性
                    if (!followRelationCache.isFollowing(article.getAuthorId(), viewerId)) {
                        throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
                    }
                }
                default -> {
                    // PUBLIC：放行
                }
            }
        }
        // 渲染：查配图 → 批量换 URL → 占位符替换为带对齐 class 的 <img>
        List<ArticleImage> images = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getArticleId, articleId)
                .orderByAsc(ArticleImage::getPositionOrder));
        if (contentHtml != null && !contentHtml.isEmpty() && !images.isEmpty()) {
            contentHtml = articleImageRenderSupport.renderImages(contentHtml, images);
        }
        // 收藏状态回填（阶段16）：匿名 false；登录用户 uk 等值查询（详情单条查询，无 N+1 问题）
        boolean favorited = viewerId != null && favoriteStateSupport.isFavorited(
                viewerId, FavoriteTargetTypeEnum.ARTICLE.getCode(), articleId);
        return new ArticleDetailVO()
                .setId(article.getId())
                .setAuthorId(article.getAuthorId())
                .setTitle(article.getTitle())
                .setSummary(article.getSummary())
                .setContentHtml(contentHtml)
                .setCoverFileId(article.getCoverFileId())
                .setCategory(article.getCategory())
                .setZone(article.getZone())
                .setStatus(owner ? article.getStatus() : ArticleStatusEnum.PUBLISHED)
                // 定时发布时间仅作者本人回显（owner 判定已含 viewerId 非空与归属比对），非本人/匿名侧不暴露
                .setScheduledPublishTime(owner ? article.getScheduledPublishTime() : null)
                .setVisibility(visibility)
                .setViewCnt(article.getViewCnt())
                .setLikeCnt(article.getLikeCnt())
                .setCommentCnt(article.getCommentCnt())
                .setTagNames(Collections.emptyList())
                .setImages(images.stream().map(articleImageSupport::toImageVO).collect(Collectors.toList()))
                .setCreateTime(article.getCreateTime())
                .setUpdateTime(article.getUpdateTime())
                .setFavorited(favorited);
    }

    /**
     * 公开文章列表（匿名可读规则：status=2 且 visibility=1；select 排除 content_html 大字段延迟加载）
     *
     * @param query 分页查询条件（zone/category 可空=全部；分页参数经 PageQuery 统一兜底钳制）
     * @return 分页结果（total/totalPages/hasNext 供前端分页组件渲染；卡片 publishTime 取 update_time）
     */
    public PageResult<ArticleCardVO> listPublic(ArticlePublicQueryDTO query) {

        /**
         * 公开列表查询流程：
         *
         * ① 组装查询条件
         *    仅“已发布+公开”的文章匿名可见；分区/分类按需过滤；
         *    排除正文大字段（列表不加载 content_html，详情页才取，减少传输量）。
         *    分类白名单校验已前移到参数绑定层（ArticlePublicQueryDTO.category 枚举 + 统一转换器，脏值 400）；
         *    前端传的 status/published/keyword 在本端点一律忽略（匿名公开列表不接受状态过滤，实现为白名单式组装：只拼 zone/category）
         *
         * ② MyBatis-Plus 分页查询
         *    页码/页大小兜底与上限钳制由 PageQuery 收口；count 由分页插件自动执行；
         *    排序固定 update_time 降序（代码显式 OrderItem 优先级最高，不开放匿名前端自定义 sortBy）。
         *
         * ③ 封装 PageResult 返回（含 total/totalPages/hasNext）。
         */

        // 公开读仅 status=2 且 visibility=1（匿名可见）；列表查询不加载 content_html 大字段（详情页才取）
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .select(Article.class, i -> !Article.COLUMN_CONTENT_HTML.equals(i.getColumn()))
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                .eq(query.getZone() != null, Article::getZone, query.getZone())
                .eq(query.getCategory() != null, Article::getCategory, query.getCategory());
        // 分页兑底（页码≥1/页大小上限钳制）与排序由 PageQuery.toMpPage 收口；显式传 update_time 降序覆盖前端 sortBy
        Page<Article> page = articleMapper.selectPage(query.toMpPage(OrderItem.desc(PageConstant.UPDATE_TIME)), wrapper);
        // 分页信息 + 记录转 VO 一行收敛（PageResult.of(IPage, Function) 工厂）
        return PageResult.of(page, this::toCardVO);
    }

    /**
     * 热门文章列表（批次E P5 定案 D-3 2026-11-03：切读 CONTENT_FEED_HOT_ZSET 热度榜）。
     *
     * ⚠️ 接口语义变化（前端需同步知会）：由「全站 view_cnt 倒序直排」改为「热度榜序」
     * （heat = view/like/comment 融合 + 时间衰减，与 /feed 热榜同源，含 zone 1/2 聚合口径）；
     * 榜空（灌榜任务未跑/已过期）或 feed.hot.enabled 关闭时回退原 view_cnt 直查
     * （降级路径低频，按定案不建 view_cnt 索引）。
     *
     * @param pageSize 取前 N 条
     * @return 文章卡片列表
     */
    public List<ArticleCardVO> listHot(int pageSize) {

        /**
         * 热门列表查询流程：
         *
         * ① 榜链路（主路径）：hot.enabled 开启时读热度榜前 N 成员，
         *    批量回查明细（补 status=2+visibility=1 过滤 + 排除 content_html，
         *    ZREM 剔除失败的残留成员兜底双保险，对齐 C3 口径），按榜序重排返回。
         *
         * ② 回退链路（降级）：榜空或开关关闭 → view_cnt 倒序直查取前 N 条（历史语义兜底）。
         */

        // pageSize 钳制到全局上限（防超大 N 拖垮数据库）
        int safeSize = Math.min(pageSize, PageConstant.MAX_PAGE_SIZE);

        // ① 榜链路：与 feed 热榜同源取 TopN（TopN 关 count，消除 orderByDesc(view_cnt) filesort）
        if (contentProperties.getFeed().getHot().isEnabled()) {
            List<Long> hotIds = feedHotRankSupport.topArticleIds(1, safeSize);
            if (!hotIds.isEmpty()) {
                // 批量回查明细（勿逐条查，防 N+1）：可见性过滤 + 排除大字段，转 Map 供榜序重排
                Map<Long, Article> articleMap = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                                .in(Article::getId, hotIds)
                                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                                .select(Article.class, i -> !Article.COLUMN_CONTENT_HTML.equals(i.getColumn())))
                        .stream()
                        .collect(Collectors.toMap(Article::getId, Function.identity()));
                // 按榜序重排（in 查询不保序，榜单名次即展示顺序）；缺失/已不可见文章跳过
                return hotIds.stream()
                        .map(articleMap::get)
                        .filter(Objects::nonNull)
                        .map(this::toCardVO)
                        .collect(Collectors.toList());
            }
        }


        // ② 回退链路：view_cnt 倒序直查；排除 content_html 大字段（延迟加载红线）
        //直接查数据库，按 view_cnt（浏览量）从高到低排序取前 N 条
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .select(Article.class, i -> !Article.COLUMN_CONTENT_HTML.equals(i.getColumn()))
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                .orderByDesc(Article::getViewCnt);
        // TopN 无分页语义：取第一页即可，count 关闭省一次全表计数
        return articleMapper
                .selectList(Page.of(1, safeSize, false), wrapper)
                .stream().map(this::toCardVO).collect(Collectors.toList());
    }

    // ==================== 私有辅助 ====================

    /**
     * 查文章并判空（del_flag 逻辑删除由 MP 自动过滤，已删即查不到 → 不存在）。
     * 与门面主类 ArticleServiceImpl#requireArticle 同款规则（读链路独立数据源，不跨类调用主类私有方法）
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

    /**
     * 文章实体 → 卡片 VO（publishTime 取 update_time，即文章发布那一刻的时间）
     *
     * @param article 文章实体（不含 content_html 大字段）
     * @return 卡片 VO
     */
    private ArticleCardVO toCardVO(Article article) {
        return new ArticleCardVO()
                .setId(article.getId())
                .setTitle(article.getTitle())
                .setSummary(article.getSummary())
                .setCoverFileId(article.getCoverFileId())
                .setCategory(article.getCategory())
                .setZone(article.getZone())
                .setAuthorId(article.getAuthorId())
                .setViewCnt(article.getViewCnt())
                .setLikeCnt(article.getLikeCnt())
                .setCommentCnt(article.getCommentCnt())
                .setPublishTime(article.getUpdateTime());
    }
}
