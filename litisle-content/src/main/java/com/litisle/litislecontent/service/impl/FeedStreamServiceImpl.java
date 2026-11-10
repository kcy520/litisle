package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.domain.dto.FeedProfile;
import com.litisle.litislecontent.domain.dto.FeedFeedbackExclusions;
import com.litisle.litislecontent.domain.dto.FeedQuery;
import com.litisle.litislecontent.domain.dto.FeedSnapshot;
import com.litisle.litislecontent.domain.dto.FeedSnapshotItem;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleStat;
import com.litisle.litislecontent.domain.entity.Book;
import com.litisle.litislecontent.domain.entity.BookStat;
import com.litisle.litislecontent.domain.entity.ContentStatWindow;
import com.litisle.litislecontent.domain.vo.BookFeedItemVO;
import com.litisle.litislecontent.domain.vo.FeedItemVO;
import com.litisle.litislecontent.domain.vo.FeedPageVO;
import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.ArticleStatMapper;
import com.litisle.litislecontent.mapper.BookMapper;
import com.litisle.litislecontent.mapper.BookStatMapper;
import com.litisle.litislecontent.mapper.ContentStatWindowMapper;
import com.litisle.litislecontent.metrics.ContentMetrics;
import com.litisle.litislecontent.service.FeedStreamService;
import com.litisle.litislecontent.service.support.FeedExposureSupport;
import com.litisle.litislecontent.service.support.FeedFeedbackSupport;
import com.litisle.litislecontent.service.support.FeedHotRankSupport;
import com.litisle.litislecontent.service.support.FeedProfileSupport;
import com.litisle.litislecontent.service.support.FeedScoreSupport;
import com.litisle.litislecontent.service.support.FeedSnapshotSupport;
import com.litisle.litislecontent.service.support.FeedItemAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.cache.CacheProperties.Redis;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 推荐流服务实现（批次十 10.3 文章流 + 10.4 书籍流）
 *
 * <p>文章流：四路召回（最热/最新/常看分区/常看分类）→ 融合打分 → 探索位与打散 →
 * 300 条快照落 Redis → cursor 偏移切片 → 翻到底自动生成下一代快照。
 * 当日曝光用 Redis Set 硬去重；已读只降权不剔除。</p>
 *
 * <p>打分口径（批次十一 11.10）：文章与书籍统一调用 {@link FeedScoreSupport}，
 * 与热度榜共用同一套公式与 yml 参数；统计量读 article_stat / book_stat 真源，
 * article.like_cnt 等冗余列只作展示镜像，不参与排序。</p>
 *
 * <p>书籍流：按用户分类偏好召回（不参与 zone）→ 收藏/阅读/新鲜度/完结偏好四权重
 * （收藏/阅读优先取 content_stat_window 的 30 天窗口值、缺失时回退累计值，再做统一平滑，
 * 平滑与候选池无关，同参数口径见 {@link FeedScoreSupport}）→ 新书 7 天保底曝光 → 同一套快照分页。</p>
 *
 * <p>批次十 10.5/10.6 收口：画像带 Redis 缓存与冷启动探索位；曝光在切片阶段二次过滤
 * （建池后到切片前可能已被曝光）；降级顺序统一为「last-good 快照 → 实时计算一页」，
 * 热度榜为空时用「最新」单独兜底，所有降级分支经 {@link #logDegrade} 留痕并回填 degraded 标记。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedStreamServiceImpl implements FeedStreamService {

    /** 文章 Mapper（四路召回 / 展示前门控回查） */
    private final ArticleMapper articleMapper;

    /** 书籍 Mapper（书籍流召回 / 展示前门控回查） */
    private final BookMapper bookMapper;

    /** 文章统计 Mapper（批次十一 11.2：排序与卡片计数统一读 article_stat） */
    private final ArticleStatMapper articleStatMapper;

    /** 书籍统计 Mapper（批次十一 11.2：排序与卡片计数统一读 book_stat） */
    private final BookStatMapper bookStatMapper;

    /** 统计窗口 Mapper（批次十一 11.3：收藏/阅读 30/90 天窗口值批量读取，翻页不聚合源表） */
    private final ContentStatWindowMapper contentStatWindowMapper;

    /**
     * 热度榜支撑（最热召回只复用 topArticleIds 取榜；打分改由 FeedScoreSupport 统一）
     * <p>作用：直接拿现成的热度榜来用，「最热」这一路召回复用它，不另算一套热度计算。</p>
     */
    private final FeedHotRankSupport feedHotRankSupport;

    /**
     * 统一打分入口（批次十一 11.10：文章/书籍排序与热度榜共用同一公式，禁止另算一套）
     * <p>作用：统一的打分公式——推荐流和热度榜共用同一套，保证分数口径一致（同一内容在两边不会算出不同的分）。</p>
     */
    private final FeedScoreSupport feedScoreSupport;

    /**
     * 用户画像聚合（reading_history 分区/分类偏好）
     * <p>作用：聚合用户画像——常看哪些分区/分类、最近读过什么文章；个性化召回和打分都依赖它，
     * 新用户没有行为数据时画像为空（冷启动），两路个性化退化为全局热门+最新。</p>
     */
    private final FeedProfileSupport feedProfileSupport;

    /**
     * 快照/曝光/种子存储
     * <p>作用：把排好序的整份推荐结果存进 Redis，翻页时按偏移取出来（保证翻页不重不乱）；
     * 当日曝光去重、会话种子、降级兜底副本的读写也全部收口在这个类。</p>
     */
    private final FeedSnapshotSupport feedSnapshotSupport;

    /**
     * 曝光/点击日聚合写入（批次十一 11.6：切片曝光批量落库，失败只告警不阻断翻页）
     * <p>作用：把每次翻页返回的内容按「天+用户」汇总落库，供后续运营统计（如点击率分析）。</p>
     */
    private final FeedExposureSupport feedExposureSupport;

    /**
     * 负反馈名单读取（批次十一 11.11：建池过滤内容/作者/分类，切片过滤单条内容）
     * <p>作用：读取用户的负反馈名单——屏蔽了哪些内容/作者/分类，建池与切片阶段据此剔除，
     * 降级兜底路径也不允许绕过该过滤。</p>
     */
    private final FeedFeedbackSupport feedFeedbackSupport;

    /** 推荐流配置（分页、召回池、权重、TTL、配额） */
    private final ContentProperties contentProperties;

    /**
     * 推荐流观测指标（降级/快照创建打点；MeterRegistry 未装配时静默空转）
     * <p>作用：监控打点——记录降级次数、快照创建次数等指标，供监控大盘观察推荐流运行健康度。</p>
     */
    private final ContentMetrics contentMetrics;

    // ==================== 文章流（10.3） ====================

// ### 看完整的降级链路（四级）
// 正常路径：读快照切片（翻页稳定、有曝光去重）
//    ↓ Redis 不可用 / 建池失败 / 快照为空
// 第一兜底：last-good 上一代成功快照（内存副本或 Redis 指针）
//    ↓ 连 last-good 也没有（比如实例刚重启、Redis 也挂了）
// ★ 最后手段：实时计算一页（realtimeArticlePage）★
//降级链路的兜底描述： Redis 挂掉时，系统从“读快照切片”退化为“每次现场算一页” ——召回、画像、打分、屏蔽过滤这些核心推荐功能全部保留（它们本来就不依赖 Redis），丢掉的是快照机制带来的 翻页稳定性 （固定排序、会话种子、曝光去重、无限续刷），并且通过`degraded` 标记把降级状态透传给前端。整个设计的哲学是：Redis 是性能与体验的优化层，而不是功能的正确性依赖——任何基础设施挂掉，业务主链路都不该断。

    /**
     * 文章流：按游标返回一页可推荐文章
     *
     * 
     * @param query 查询入参（身份/游标/快照/页大小/分区筛选）
     * @return 游标分页结果
     */
    @Override
    public FeedPageVO<FeedItemVO> feedArticles(FeedQuery query) {
      
// 前端带上 snapshotId + cursor（偏移量）
//     ↓
// 步骤5：readSnapshot(scope, snapshotId) —— 读同一份快照
//     ↓
// 步骤7：sliceArticlePage —— 按 cursor 偏移切出 [offset, offset+pageSize) 这一页
      
        // 步骤1：校验召回模式（P0 仅 db；配置为 es 时告警并回落，保证不出现不可用空分支）
        warnIfEsRecallConfigured();

        // 步骤2：分页参数收口（默认取配置，超上限截断）
        int pageSize = resolvePageSize(query.getPageSize());

        // 步骤3：身份与快照作用域（分区筛选参与作用域，避免不同筛选条件共用一份池）
        String identity = feedSnapshotSupport.resolveIdentity(query);
        // 分区筛选码（null 表示不筛选）
        Integer zoneCode = query.getZone() == null ? null : query.getZone().getCode();
        String scope = feedSnapshotSupport.resolveScope(FeedConstant.STREAM_ARTICLE, identity, zoneCode);
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        // 游标解析结果（偏移 + 是否需要前端重置）
        CursorState cursorState = resolveCursor(query);

        // 步骤4：Redis 不可用 → 先取 last-good 快照兜底，没有再实时计算一页
        if (!feedSnapshotSupport.isRedisAvailable()) {
            // last-good 此时来自本实例内存副本（Redis 不可用时唯一可用来源）
            FeedSnapshot lastGood = feedSnapshotSupport.readLastGood(scope);
            if (lastGood != null) {
                logDegrade(FeedConstant.DEGRADED_LAST_GOOD, FeedConstant.STREAM_ARTICLE, scope,
                        "Redis 不可用，返回 last-good 快照");
                // 兜底页同样带入当前用户，供负反馈过滤（屏蔽作者/分类不得借降级路径放出）
                return lastGoodArticlePage(lastGood, pageSize, cursorState, query.getUserId());
            }
            logDegrade(FeedConstant.DEGRADED_REDIS_DOWN, FeedConstant.STREAM_ARTICLE, scope,
                    "Redis 不可用且无 last-good，实时计算一页");
            return realtimeArticlePage(query, pageSize, cursorState, FeedConstant.DEGRADED_REDIS_DOWN);
        }

        // 步骤5：读快照（失效要求前端重置；翻到底则生成下一代快照续刷）
        FeedSnapshot snapshot = null;
        if (StringUtils.hasText(query.getSnapshotId()) && !cursorState.isReset()) {
            snapshot = feedSnapshotSupport.readSnapshot(scope, query.getSnapshotId());
            if (snapshot == null) {
                // 快照过期/被裁剪：返回 reset=true，前端清空列表重新渲染
                cursorState = new CursorState(FeedConstant.CURSOR_START_OFFSET, true);
            } else if (cursorState.getOffset() >= snapshot.getItems().size()) {
                // 翻到底：生成下一代快照（snapshotId 递增），前端按新快照续刷、不清空已加载内容
                snapshot = null;
            }
        }

        // 步骤6：需要建池（首次 / 快照失效 / 续池）
        if (snapshot == null) {
            snapshot = buildArticleSnapshot(query, identity, scope, stream);
            if (snapshot == null) {
                // 建池失败（代次生成/写入异常）：先 last-good，再退实时一页
                FeedSnapshot lastGood = feedSnapshotSupport.readLastGood(scope);
                if (lastGood != null) {
                    logDegrade(FeedConstant.DEGRADED_LAST_GOOD, FeedConstant.STREAM_ARTICLE, scope,
                            "快照建池失败，返回 last-good 快照");
                    // 兜底页同样带入当前用户，供负反馈过滤
                    return lastGoodArticlePage(lastGood, pageSize, cursorState, query.getUserId());
                }
                logDegrade(FeedConstant.DEGRADED_BUILD_FAILED, FeedConstant.STREAM_ARTICLE, scope,
                        "快照建池失败且无 last-good，实时计算一页");
                return realtimeArticlePage(query, pageSize, cursorState, FeedConstant.DEGRADED_BUILD_FAILED);
            }
            // 新快照从头部开始切片（reset 语义沿用：失效时为 true，续池时为 false）
            cursorState = new CursorState(FeedConstant.CURSOR_START_OFFSET, cursorState.isReset());
        }

        // 步骤6.1：空池兜底（热度榜空、最新也为空）→ 优先 last-good，避免返回空列表
        if (snapshot.getItems().isEmpty()) {
            FeedSnapshot lastGood = feedSnapshotSupport.readLastGood(scope);
            if (lastGood != null) {
                logDegrade(FeedConstant.DEGRADED_LAST_GOOD, FeedConstant.STREAM_ARTICLE, scope,
                        "快照为空，返回 last-good 快照");
                // 兜底页同样带入当前用户，供负反馈过滤
                return lastGoodArticlePage(lastGood, pageSize, cursorState, query.getUserId());
            }
        }

        // 步骤7：切片 + 展示前回查 + 曝光写入（切片阶段再做一次曝光过滤）
        return sliceArticlePage(snapshot, cursorState, pageSize, identity, stream, query.getUserId());
    }


    /**
     * 建文章快照：四路召回 → 去重/曝光过滤 → 打分排序 → 探索位与打散 → 落 Redis
     *
     * 不是漏了，而是 ES 初筛这一路本来就没实现，当前版本就是直接查 MySQL 。这是有意的分阶段设计：
     * 
     * 
     * @param query    查询入参
     * @param identity 身份标识
     * @param scope    快照作用域
     * @param stream   推荐流配置
     * @return 快照对象；Redis 写入失败返回 null（调用方降级）
     */
    private FeedSnapshot buildArticleSnapshot(FeedQuery query, String identity, String scope,
                                              ContentProperties.Stream stream) {
        // 用户画像：聚合「常看哪些分区/分类、最近读过什么」，个性化召回和打分都依赖它
        // （匿名/无行为数据 → coldStart=true，两路个性化退化全局；10.5 走 Redis 缓存）
        FeedProfile profile = feedProfileSupport.load(query);
        // 每路召回条数
        int perPath = stream.getRecallLimitPerPath();
        // 候选池（LinkedHashMap 去重，同一文章多路命中只保留一份）
        Map<Long, Article> candidates = new LinkedHashMap<>();

        // 路1：最热——直接拿现成的热度榜来用（复用热度榜 ZSet，不另算一套热度计算）
        List<Long> hotIds = feedHotRankSupport.topArticleIds(1, perPath);
        // 热度榜为空标记：榜空时改由「最新」单独兜底，并把最新召回量放大到整池
        boolean hotEmpty = hotIds.isEmpty();
        // 最新路召回条数：榜空时放大到池容量，避免只靠 perPath 条最新内容撑不起长刷
        int latestLimit = hotEmpty ? stream.getPoolSize() : perPath;
        if (hotEmpty) {
            // 榜空属于降级分支：统一留痕，并随快照落 degraded 标记
            logDegrade(FeedConstant.DEGRADED_HOT_EMPTY, FeedConstant.STREAM_ARTICLE, scope,
                    "热度榜为空，最新单独兜底（latestLimit=" + latestLimit + "）");
        }
        if (!hotIds.isEmpty()) {
            loadArticlesByIds(hotIds).forEach(article -> candidates.putIfAbsent(article.getId(), article));
        }

        // 路2：最新（按 create_time 倒序，走 idx_create_time；榜空时条数放大到池容量）
        articleMapper.selectList(Page.of(1, latestLimit, false), articleGate())
                .forEach(article -> candidates.putIfAbsent(article.getId(), article));

        // 路3/路4：常看分区 / 常看分类（无画像时跳过，两路个性化退化为全局热门+最新）
        if (!profile.isColdStart()) {
            if (!profile.getPreferredZones().isEmpty()) {
                LambdaQueryWrapper<Article> zoneWrapper = articleGate()
                        .in(Article::getZone, profile.getPreferredZones());
                articleMapper.selectList(Page.of(1, perPath, false), zoneWrapper)
                        .forEach(article -> candidates.putIfAbsent(article.getId(), article));
            }
            if (!profile.getPreferredCategories().isEmpty()) {
                LambdaQueryWrapper<Article> categoryWrapper = articleGate()
                        .in(Article::getCategory, profile.getPreferredCategories());
                articleMapper.selectList(Page.of(1, perPath, false), categoryWrapper)
                        .forEach(article -> candidates.putIfAbsent(article.getId(), article));
            }
        }

        // 分区筛选入参：非空时只保留该分区的候选（快照作用域已按分区隔离）
        if (query.getZone() != null) {
            candidates.entrySet().removeIf(entry -> entry.getValue().getZone() != query.getZone());
        }

        // 批次十一 11.11：建池阶段过滤负反馈名单——作用：读取用户屏蔽的内容/作者/分类，命中即从候选池剔除
        // （内容/作者/分类三类都在这里生效；名单按「用户 + 流」缓存；匿名用户返回空名单，不做长期屏蔽）
        FeedFeedbackExclusions feedback = feedFeedbackSupport.loadExclusions(
                query.getUserId(), FeedConstant.STREAM_ARTICLE);
        if (!feedback.isEmpty()) {
            candidates.entrySet().removeIf(entry -> {
                // 当前候选文章
                Article article = entry.getValue();
                // 命中任一屏蔽维度即从候选池剔除（不进入打分，也不会进入后续快照）
                return feedback.getContentIds().contains(article.getId())
                        || feedback.getAuthorIds().contains(article.getAuthorId())
                        || (article.getCategory() != null
                        && feedback.getCategories().contains(article.getCategory().getName()));
            });
        }

        // 当日曝光硬去重：已曝光的不再进池（已读降权走打分，不在此剔除）
        Set<Long> exposed = feedSnapshotSupport.loadTodayExposed(FeedConstant.STREAM_ARTICLE, identity);
        if (!exposed.isEmpty()) {
            candidates.keySet().removeAll(exposed);
        }

        // 生成会话种子（同一快照窗口内稳定，跨会话可变）
        long seed = feedSnapshotSupport.resolveSessionSeed(scope);
        // 打分排序 + 探索位 + 打散
        List<FeedSnapshotItem> items = rankArticles(candidates, profile, stream, stream.getPoolSize(), seed);

        // 生成快照代次（INCR）；Redis 异常返回 null，由调用方降级
        String snapshotId = feedSnapshotSupport.nextSnapshotId(scope);
        if (snapshotId == null) {
            return null;
        }
        FeedSnapshot snapshot = new FeedSnapshot()
                .setSnapshotId(snapshotId)
                .setSeed(seed)
                .setItems(items)
                // 热度榜为空的降级标记随快照落库，续页返回体也能识别同一代快照的降级态
                .setDegraded(hotEmpty ? FeedConstant.DEGRADED_HOT_EMPTY : null);
        // 写快照落 Redis：同时登记活动快照索引（自动裁剪旧代次）、记录 last-good 兜底副本
        feedSnapshotSupport.writeSnapshot(scope, snapshot);
        // 快照创建指标（监控打点）：cold_start 维度来自本次画像，便于区分新用户与老用户建池量
        contentMetrics.recordFeedSnapshotCreated(FeedConstant.STREAM_ARTICLE, profile.isColdStart());
        return snapshot;
    }

    /**
     * 文章融合打分并排序（热度/新鲜度/分区匹配/分类匹配四项加权 + 已读扣分）
     *
     * @param candidates 候选文章（按 ID 去重后）
     * @param profile    用户画像
     * @param stream     推荐流配置
     * @param targetSize 目标池大小
     * @param seed       会话种子（探索位抽样用）
     * @return 排好序的条目列表（长度不超过 targetSize）
     */
    private List<FeedSnapshotItem> rankArticles(Map<Long, Article> candidates, FeedProfile profile,
                                                ContentProperties.Stream stream, int targetSize, long seed) {
        if (candidates.isEmpty()) {
            return new ArrayList<>();
        }
        // 批次十一 11.2：计数统一读 article_stat，按 ID 集合一次批量查询覆盖整个候选池
        // （不逐条查，也不再读 article 表上的镜像计数字段）；统计行缺失按 0
        Map<Long, ArticleStat> statById = loadArticleStats(candidates.keySet());
        // 批次十一 11.10：文章收藏数优先读 30/90 天窗口值，缺失回退 article_stat 累计值
        ContentProperties.Window windowConfig = contentProperties.getStat().getWindow();
        // 窗口行：整池一次批量取回两档窗口（窗口任务未覆盖时 Map 为空，读侧自动回退累计值）
        Map<Long, Map<Integer, ContentStatWindow>> windowById = loadWindowStats(
                ContentBizConstant.STAT_WINDOW_TARGET_ARTICLE, candidates.keySet(),
                windowConfig.getShortDays(), windowConfig.getLongDays());

        // 逐篇算分：统一调用 FeedScoreSupport（与热度榜同一函数，禁止另算一套）
        List<FeedSnapshotItem> scored = new ArrayList<>(candidates.size());
        for (Article article : candidates.values()) {
            // 统计行缺失（新文章尚无互动）按 0 参与公式
            ArticleStat stat = statById.get(article.getId());
            // 收藏数兜底值：窗口缺失时用 article_stat 累计收藏数
            long fallbackCollect = stat == null || stat.getCollectCount() == null ? 0L : stat.getCollectCount();
            // 收藏数：优先窗口值，缺失回退累计值
            long collectCount = feedScoreSupport.resolveWindowCount(
                    windowById, article.getId(), windowConfig, true, fallbackCollect);
            // 分区匹配：命中用户常看分区记 1，否则 0（冷启动画像为空集合，自然为 0）
            double zoneMatch = profile.getPreferredZones().contains(article.getZone()) ? 1.0 : 0.0;
            // 分类匹配：命中用户常看分类记 1，否则 0
            double categoryMatch = profile.getPreferredCategories().contains(article.getCategory()) ? 1.0 : 0.0;
            // 统一打分：推荐流和热度榜共用同一套公式，保证分数口径一致（同一内容两边不会算出不同的分）；
            // 浏览/点赞/评论/收藏/新鲜度/分区/分类权重全部来自 yml
            double score = feedScoreSupport.computeArticleScore(
                    stat, collectCount, article.getCreateTime(), zoneMatch, categoryMatch);
            // 已读降权（不剔除）：窗口内已读文章扣分，保证长刷仍能看到但排位靠后
            if (profile.getReadArticleIds().contains(article.getId())) {
                score -= stream.getReadPenalty();
            }
            scored.add(new FeedSnapshotItem().setId(article.getId()).setScore(score));
        }

        // 分数倒序；同分用 ID 倒序做稳定 tie-breaker（避免翻页顺序抖动）
        scored.sort(Comparator.comparingDouble(FeedSnapshotItem::getScore).reversed()
                .thenComparing(FeedSnapshotItem::getId));
        // 冷启动没有分区/分类个性化信号：探索位比例上调（配置 coldStartExplorationRatio，默认 0.30）
        double explorationRatio = profile.isColdStart()
                ? stream.getColdStartExplorationRatio()
                : stream.getExplorationRatio();
        // 探索位 + 打散
        List<FeedSnapshotItem> explored = applyExploration(scored, targetSize, explorationRatio, seed);
        return applyDiversity(explored, candidates,
                stream.getMaxConsecutiveSameAuthor(), stream.getMaxConsecutiveSameCategory());
    }

    /**
     * 文章页切片：按偏移取一页 → 展示前门控回查 → 标记当日曝光 → 组装分页结果
     *
     * （标记当日曝光—只要接口把这页返回给了客户端，整页条目就算曝光，无论用户是否真的滚动看到）
     * 
     * @param snapshot    快照
     * @param cursorState 游标状态
     * @param pageSize    页大小
     * @param identity    身份标识
     * @param stream      推荐流配置
     * @param userId      当前登录用户ID（匿名 null；用于切片时剔除「不感兴趣」单条内容）
     * @return 分页结果
     */
    private FeedPageVO<FeedItemVO> sliceArticlePage(FeedSnapshot snapshot, CursorState cursorState,
                                                    int pageSize, String identity, ContentProperties.Stream stream,
                                                    Long userId) {

// 从快照里取出`[cursor, cursor+pageSize)` 这一页，但不是机械切割：
// - 切片阶段二次过滤 ：建池时过滤过曝光，但从建池到切片的间隙里内容可能已被曝光（比如并发请求），所以切片时再查一次曝光集合和“不感兴趣”名单， 已曝光/被屏蔽的条目直接跳过，游标照常前进 ，避免同一条内容卡住分页；
// - 展示前门控回查 ：切片结果回数据库再查一遍，下架/转私密/软删的内容直接丢弃—— 绝不让失效内容出现在用户眼前 ，哪怕快照是几分钟前建的；
// - 标记曝光 ：返回的这页全部计入当日曝光，同时写一条曝光日聚合记录（失败只告警不阻断翻页）；
// - 游标语义 ：cursor = “已消费条数”（含被跳过的），返回给前端；翻到池末尾但池是满的，说明可以生成下一代快照继续刷（无限刷的关键）。

// 降级链路
// 正常路径：读快照切片（翻页稳定 + 曝光去重）
//    ↓ Redis 不可用 / 建池失败 / 快照为空
// 第一兜底：last-good 上一代成功快照（lastGoodArticlePage）
//    ↓ 连 last-good 都没有（实例刚重启 + Redis 也挂了）
// 最后手段：实时计算一页（realtimeArticlePage）
        
        // 快照条目全集（已按分数与打散规则排好序）
        List<FeedSnapshotItem> all = snapshot.getItems();
        // 批次十一 11.11：旧快照里被「不感兴趣」的内容在切片阶段直接跳过（作者/分类屏蔽在建池时生效）
        // 名单按用户+流缓存，命中缓存时不产生 DB 查询
        Set<Long> blockedContentIds = feedFeedbackSupport
                .loadExclusions(userId, FeedConstant.STREAM_ARTICLE).getContentIds();
        // 本页起点（cursor 即已消费条数，from 恒从快照头部计偏移）
        int from = (int) Math.min(cursorState.getOffset(), all.size());
        // 当日已曝光集合：建池时过滤过一次，但建池后翻页/并发续池可能已曝光，切片阶段必须再过滤
        Set<Long> exposed = feedSnapshotSupport.loadTodayExposed(FeedConstant.STREAM_ARTICLE, identity);
        // 扫描指针：从 from 向后找满一页未曝光条目；被跳过的已曝光条目同样计入已消费
        int scanTo = from;
        // 本页对象 ID（保持快照顺序；长度不超过 pageSize）
        List<Long> ids = new ArrayList<>(pageSize);
        while (scanTo < all.size() && ids.size() < pageSize) {
            Long articleId = all.get(scanTo).getId();
            scanTo++;
            // 已曝光或已被单条屏蔽的条目直接跳过，游标照常前进，避免同一条目反复阻塞分页
            if (exposed.contains(articleId) || blockedContentIds.contains(articleId)) {
                continue;
            }
            ids.add(articleId);
        }

        // 展示前回查门控：下架/转私密/软删的旧条目直接丢弃，保证不会曝光失效内容
        // 已读降权只作用于建池打分（rankArticles 里 score -= readPenalty），此处绝不按已读剔除条目
        Map<Long, Article> visible = loadArticlesByIds(ids).stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (left, right) -> left));
        // 批次十一 11.2：卡片计数改读 article_stat，本页一次批量查询（缺失行按 0；翻页不做聚合）
        Map<Long, ArticleStat> statById = loadArticleStats(ids);
        List<FeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(article -> FeedItemAssembler.toArticleItem(article, statById.get(article.getId())))
                .toList();

        // 本页实际返回的条目计入当日曝光（后续切片/下一代快照都会按该集合再过滤）
        feedSnapshotSupport.markExposed(FeedConstant.STREAM_ARTICLE, identity,
                items.stream().map(FeedItemVO::getArticleId).toList());
        // 批次十一 11.6：曝光日聚合按页批量 upsert——作用：把本页返回内容按「天+用户」汇总落库，供运营统计
        // （Set 已保证同身份同日同条一次，写入失败只告警）
        feedExposureSupport.recordExposure(FeedConstant.STREAM_ARTICLE, identity,
                items.stream().map(FeedItemVO::getArticleId).toList());

        // 是否还有下一页：池满（可续下一代）或本页未到快照末尾
        boolean hasMore = all.size() >= stream.getPoolSize() || scanTo < all.size();
        // 下一页游标：已消费条数（含被曝光跳过的条目；到快照末尾且池未满时为空，前端停止加载）
        String nextCursor = hasMore ? String.valueOf(scanTo) : null;
        return new FeedPageVO<FeedItemVO>()
                .setItems(items)
                .setCursor(nextCursor)
                .setSnapshotId(snapshot.getSnapshotId())
                .setHasMore(hasMore)
                .setReset(cursorState.isReset())
                // 建池时的降级标记（如 hot-empty）随页返回，续页同样可识别
                .setDegraded(snapshot.getDegraded());
    }

    /**
     * 文章流降级页：实时计算一页（不建 300 条池、不落 Redis、不写曝光）
     *
     * @param query          查询入参
     * @param pageSize       页大小
     * @param cursorState    游标状态（仅沿用 reset 语义）
     * @param degradedReason 降级原因标记（FeedConstant.DEGRADED_*，回填响应体供前端识别）
     * @return 一次性首页（无 cursor / 无 snapshotId，前端停止续刷）
     */
    private FeedPageVO<FeedItemVO> realtimeArticlePage(FeedQuery query, int pageSize, CursorState cursorState,
                                                       String degradedReason) {
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        // 画像（10.5 走 Redis 缓存；Redis 不可用时缓存自动未命中，回源 DB 聚合）
        FeedProfile profile = feedProfileSupport.load(query);
        // 降级只取首页所需样本（页大小的 2 倍），不在降级路径里算满池
        int sampleSize = pageSize * 2;
        Map<Long, Article> candidates = new LinkedHashMap<>();

        // 最新一路
        articleMapper.selectList(Page.of(1, sampleSize, false), articleGate())
                .forEach(article -> candidates.putIfAbsent(article.getId(), article));
        // 最热一路（复用热度榜：直接拿现成的热度榜，不另算一套）
        List<Long> hotIds = feedHotRankSupport.topArticleIds(1, sampleSize);
        if (!hotIds.isEmpty()) {
            loadArticlesByIds(hotIds).forEach(article -> candidates.putIfAbsent(article.getId(), article));
        }
        // 分区筛选
        if (query.getZone() != null) {
            candidates.entrySet().removeIf(entry -> entry.getValue().getZone() != query.getZone());
        }
        // 批次十一 11.11：降级实时页同样先过滤负反馈名单，避免降级路径绕过屏蔽
        FeedFeedbackExclusions feedback = feedFeedbackSupport.loadExclusions(
                query.getUserId(), FeedConstant.STREAM_ARTICLE);
        if (!feedback.isEmpty()) {
            candidates.entrySet().removeIf(entry -> {
                // 当前候选文章
                Article article = entry.getValue();
                // 命中内容/作者/分类任一屏蔽维度即剔除
                return feedback.getContentIds().contains(article.getId())
                        || feedback.getAuthorIds().contains(article.getAuthorId())
                        || (article.getCategory() != null
                        && feedback.getCategories().contains(article.getCategory().getName()));
            });
        }

        // 降级没有稳定会话种子：用随机种子打散，保证每次降级内容不完全相同
        List<FeedSnapshotItem> ranked = rankArticles(candidates, profile, stream, pageSize,
                ThreadLocalRandom.current().nextLong());
        List<Long> ids = ranked.stream().map(FeedSnapshotItem::getId).toList();
        Map<Long, Article> visible = loadArticlesByIds(ids).stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (left, right) -> left));
        // 批次十一 11.2：降级页同样读 article_stat（一次批量查询，缺失行按 0）
        Map<Long, ArticleStat> statById = loadArticleStats(ids);
        List<FeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(article -> FeedItemAssembler.toArticleItem(article, statById.get(article.getId())))
                .toList();

        // 降级页没有快照：cursor/snapshotId 为空，hasMore=false，前端停止加载
        return new FeedPageVO<FeedItemVO>()
                .setItems(items)
                .setCursor(null)
                .setSnapshotId(null)
                .setHasMore(false)
                .setReset(cursorState.isReset())
                // 回填降级原因（redis-down / build-failed），前端据此提示
                .setDegraded(degradedReason);
    }

    /**
     * 文章流 last-good 降级页：返回上一代成功快照的首页（terminal 页：无游标、不可续刷）
     *
     * <p>使用场景：Redis 不可用或快照建池失败时，优先让用户继续看到上一代成功内容，
     * 而不是直接掉到实时计算（实时计算会丢排序稳定性与曝光去重）。
     * 本页不写曝光记录：降级页是一次性兜底，且 Redis 很可能不可用。</p>
     *
     * @param snapshot    上一代成功快照（来自 FeedSnapshotSupport 内存副本或 Redis 指针）
     * @param pageSize    页大小
     * @param cursorState 游标状态（仅沿用 reset 语义）
     * @param userId      当前登录用户ID（匿名 null 时名单为空，不做过滤）
     * @return 一次性首页（degraded=last-good）
     *
     * <p>批次十一 11.11 修复：兜底页同样应用负反馈名单（内容/作者/分类）。
     * 之前只在建池与切片阶段过滤，空池回退 last-good 时会把已屏蔽作者的内容重新放出。</p>
     */
    private FeedPageVO<FeedItemVO> lastGoodArticlePage(FeedSnapshot snapshot, int pageSize,
                                                       CursorState cursorState, Long userId) {
        // 快照条目全集
        List<FeedSnapshotItem> all = snapshot.getItems();
        // 先按负反馈名单过滤整代快照，再取首页，避免被屏蔽条目挤占页大小
        List<Long> filteredIds = filterArticleIdsByFeedback(
                all.stream().map(FeedSnapshotItem::getId).toList(), userId);
        // 只取首页：last-good 不提供续刷游标，避免客户端拿旧代次快照继续翻页
        int to = Math.min(pageSize, filteredIds.size());
        List<Long> ids = filteredIds.subList(0, to);

        // 展示前门控仍回查：Redis 不可用不影响 DB 可用性，下架/私密内容绝不能借降级路径放出
        Map<Long, Article> visible = loadArticlesByIds(ids).stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (left, right) -> left));
        // 批次十一 11.2：last-good 页同样读 article_stat（一次批量查询，缺失行按 0）
        Map<Long, ArticleStat> statById = loadArticleStats(ids);
        List<FeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(article -> FeedItemAssembler.toArticleItem(article, statById.get(article.getId())))
                .toList();

        // 降级页无 cursor / 无 snapshotId：前端停止续刷；reset 沿用入参语义
        return new FeedPageVO<FeedItemVO>()
                .setItems(items)
                .setCursor(null)
                .setSnapshotId(null)
                .setHasMore(false)
                .setReset(cursorState.isReset())
                .setDegraded(FeedConstant.DEGRADED_LAST_GOOD);
    }

    /**
     * 按负反馈名单过滤文章 ID（批次十一 11.11 修复，供 last-good 兜底页复用）。
     *
     * <p>过滤三档：单条内容ID、作者ID、分类名。分类按 {@link ArticleCategoryEnum#getName()} 比较，
     * 与 {@code feed_feedback.target_key} 存的中文分类名同口径；文章分类为空时不做分类匹配。</p>
     *
     * @param ids    待过滤文章 ID（保持原顺序）
     * @param userId 当前登录用户ID（匿名 null 时名单为空，原样返回）
     * @return 过滤后的文章 ID
     */
    private List<Long> filterArticleIdsByFeedback(List<Long> ids, Long userId) {
        // 空集合无需回查
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        // 当前用户当前流的屏蔽名单（内容/作者/分类三类）
        FeedFeedbackExclusions feedback = feedFeedbackSupport.loadExclusions(userId, FeedConstant.STREAM_ARTICLE);
        // 空名单直接原样返回，省一次文章回查
        if (feedback.isEmpty()) {
            return ids;
        }
        // 一次批量回查作者与分类（最多一个池容量，不做逐条查询）
        Map<Long, Article> articleById = loadArticlesByIds(ids).stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (left, right) -> left));
        return ids.stream().filter(id -> {
            // 回查不到的文章交给后续门控丢弃（此处放行不保留）
            Article article = articleById.get(id);
            if (article == null) {
                return true;
            }
            // 命中内容/作者/分类任一维度即剔除
            return !feedback.getContentIds().contains(id)
                    && !feedback.getAuthorIds().contains(article.getAuthorId())
                    && !(article.getCategory() != null
                    && feedback.getCategories().contains(article.getCategory().getName()));
        }).toList();
    }

    // ==================== 书籍流（10.4） ====================

    /**
     * 书籍流：按游标返回一页可推荐书籍（不参与 zone，按 category 组织）
     *
     * @param query 查询入参
     * @return 游标分页结果
     */
    @Override
    public FeedPageVO<BookFeedItemVO> feedBooks(FeedQuery query) {
        // 步骤1：召回模式校验（书籍流 P1 仍走数据库，此处仅统一日志口径）
        warnIfEsRecallConfigured();

        // 步骤2：分页参数收口
        int pageSize = resolvePageSize(query.getPageSize());

        // 步骤3：身份与快照作用域（书籍流不参与 zone，作用域不带分区后缀）
        String identity = feedSnapshotSupport.resolveIdentity(query);
        String scope = feedSnapshotSupport.resolveScope(FeedConstant.STREAM_BOOK, identity, null);
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        CursorState cursorState = resolveCursor(query);

        // 步骤4：Redis 不可用 → 先取 last-good 快照兜底，没有再实时计算一页
        if (!feedSnapshotSupport.isRedisAvailable()) {
            // last-good 此时来自本实例内存副本（Redis 不可用时唯一可用来源）
            FeedSnapshot lastGood = feedSnapshotSupport.readLastGood(scope);
            if (lastGood != null) {
                logDegrade(FeedConstant.DEGRADED_LAST_GOOD, FeedConstant.STREAM_BOOK, scope,
                        "Redis 不可用，返回 last-good 快照");
                // 兜底页同样带入当前用户，供负反馈过滤
                return lastGoodBookPage(lastGood, pageSize, cursorState, query.getUserId());
            }
            logDegrade(FeedConstant.DEGRADED_REDIS_DOWN, FeedConstant.STREAM_BOOK, scope,
                    "Redis 不可用且无 last-good，实时计算一页");
            return realtimeBookPage(query, pageSize, cursorState, FeedConstant.DEGRADED_REDIS_DOWN);
        }

        // 步骤5：读快照（失效重置 / 翻到底续池）
        FeedSnapshot snapshot = null;
        if (StringUtils.hasText(query.getSnapshotId()) && !cursorState.isReset()) {
            snapshot = feedSnapshotSupport.readSnapshot(scope, query.getSnapshotId());
            if (snapshot == null) {
                cursorState = new CursorState(FeedConstant.CURSOR_START_OFFSET, true);
            } else if (cursorState.getOffset() >= snapshot.getItems().size()) {
                snapshot = null;
            }
        }

        // 步骤6：建池
        if (snapshot == null) {
            snapshot = buildBookSnapshot(query, identity, scope, stream);
            if (snapshot == null) {
                // 建池失败（代次生成/写入异常）：先 last-good，再退实时一页
                FeedSnapshot lastGood = feedSnapshotSupport.readLastGood(scope);
                if (lastGood != null) {
                    logDegrade(FeedConstant.DEGRADED_LAST_GOOD, FeedConstant.STREAM_BOOK, scope,
                            "快照建池失败，返回 last-good 快照");
                    // 兜底页同样带入当前用户，供负反馈过滤
                    return lastGoodBookPage(lastGood, pageSize, cursorState, query.getUserId());
                }
                logDegrade(FeedConstant.DEGRADED_BUILD_FAILED, FeedConstant.STREAM_BOOK, scope,
                        "快照建池失败且无 last-good，实时计算一页");
                return realtimeBookPage(query, pageSize, cursorState, FeedConstant.DEGRADED_BUILD_FAILED);
            }
            cursorState = new CursorState(FeedConstant.CURSOR_START_OFFSET, cursorState.isReset());
        }

        // 步骤6.1：空池兜底 → 优先 last-good，避免返回空列表
        if (snapshot.getItems().isEmpty()) {
            FeedSnapshot lastGood = feedSnapshotSupport.readLastGood(scope);
            if (lastGood != null) {
                logDegrade(FeedConstant.DEGRADED_LAST_GOOD, FeedConstant.STREAM_BOOK, scope,
                        "快照为空，返回 last-good 快照");
                // 兜底页同样带入当前用户，供负反馈过滤
                return lastGoodBookPage(lastGood, pageSize, cursorState, query.getUserId());
            }
        }

        // 步骤7：切片 + 门控回查 + 计数回填 + 曝光写入（切片阶段再做一次曝光过滤）
        return sliceBookPage(snapshot, cursorState, pageSize, identity, stream, query.getUserId());
    }

    /**
     * 建书籍快照：按分类偏好召回 → 三项计数聚合 → 四权重打分 → 新书保底 → 落 Redis
     *
     * @param query    查询入参
     * @param identity 身份标识
     * @param scope    快照作用域
     * @param stream   推荐流配置
     * @return 快照对象；Redis 写入失败返回 null（调用方降级）
     */
    private FeedSnapshot buildBookSnapshot(FeedQuery query, String identity, String scope,
                                           ContentProperties.Stream stream) {
        // 用户画像：聚合「常看哪些分类、偏好完结还是连载」，个性化召回和打分都依赖它
        // （分类偏好与完结偏好来源；10.5 走 Redis 缓存）
        FeedProfile profile = feedProfileSupport.load(query);
        // 书籍门控 + 默认排序（新建时间倒序，仅决定入池先后，最终按打分重排）
        LambdaQueryWrapper<Book> wrapper = bookGate();

        // 有分类偏好时按偏好分类召回（书籍不参与 zone；冷启动不限定分类，取全局候选）
        if (!profile.isColdStart() && !profile.getPreferredCategories().isEmpty()) {
            wrapper.in(Book::getCategory, profile.getPreferredCategories().stream()
                    .map(ArticleCategoryEnum::getName)
                    .toList());
        }
        List<Book> books = bookMapper.selectList(Page.of(1, stream.getPoolSize(), false), wrapper);

        // 批次十一 11.11：建池阶段过滤负反馈名单——作用：读取用户屏蔽的内容/作者/分类，命中即从候选池剔除
        // （书籍按内容ID/作者/分类三类命中剔除）
        FeedFeedbackExclusions feedback = feedFeedbackSupport.loadExclusions(
                query.getUserId(), FeedConstant.STREAM_BOOK);
        if (!feedback.isEmpty()) {
            books = books.stream().filter(book ->
                    // 命中任一屏蔽维度即从候选池剔除
                    !feedback.getContentIds().contains(book.getId())
                            && !feedback.getAuthorIds().contains(book.getAuthorId())
                            && !feedback.getCategories().contains(book.getCategory())).toList();
        }

        // 当日曝光硬去重（书籍与文章各自独立作用域，避免同号 ID 互相误判）
        Set<Long> exposed = feedSnapshotSupport.loadTodayExposed(FeedConstant.STREAM_BOOK, identity);
        if (!exposed.isEmpty()) {
            books = books.stream().filter(book -> !exposed.contains(book.getId())).toList();
        }

        // 打分排序（含新书保底）
        List<FeedSnapshotItem> items = rankBooks(books, profile, stream, stream.getPoolSize());
        if (items.isEmpty()) {
            // 无候选：仍返回空快照，避免前端每次请求都触发重建
            String emptySnapshotId = feedSnapshotSupport.nextSnapshotId(scope);
            if (emptySnapshotId == null) {
                return null;
            }
            FeedSnapshot emptySnapshot = new FeedSnapshot()
                    .setSnapshotId(emptySnapshotId)
                    .setItems(new ArrayList<>());
            feedSnapshotSupport.writeSnapshot(scope, emptySnapshot);
            // 空池快照同样算一次创建（cold_start 维度用于观察新用户空池率）
            contentMetrics.recordFeedSnapshotCreated(FeedConstant.STREAM_BOOK, profile.isColdStart());
            return emptySnapshot;
        }

        // 生成快照代次并落 Redis
        String snapshotId = feedSnapshotSupport.nextSnapshotId(scope);
        if (snapshotId == null) {
            return null;
        }
        FeedSnapshot snapshot = new FeedSnapshot()
                .setSnapshotId(snapshotId)
                .setItems(items);
        // 写快照落 Redis：同时登记活动快照索引（自动裁剪旧代次）、记录 last-good 兜底副本
        feedSnapshotSupport.writeSnapshot(scope, snapshot);
        // 快照创建指标（监控打点）：书籍流与文章流共用同一套指标名，靠 stream 标签区分
        contentMetrics.recordFeedSnapshotCreated(FeedConstant.STREAM_BOOK, profile.isColdStart());
        return snapshot;
    }

    /**
     * 书籍打分排序：收藏/阅读人数 log1p 归一化 + 新鲜度 + 完结偏好，叠加新书 7 天保底
     *
     * @param books      候选书籍（门控后）
     * @param profile    用户画像
     * @param stream     推荐流配置
     * @param targetSize 目标池大小
     * @return 排好序的条目列表
     */
    private List<FeedSnapshotItem> rankBooks(List<Book> books, FeedProfile profile,
                                             ContentProperties.Stream stream, int targetSize) {
        if (books.isEmpty()) {
            return new ArrayList<>();
        }
        // 书籍 ID 集合（统计表批量查询用）
        List<Long> bookIds = books.stream().map(Book::getId).toList();
        // 批次十一 11.2：收藏/阅读人数改读 book_stat，整池一次批量查询（缺行按 0，不逐本查、不再聚合源表）
        Map<Long, BookStat> statById = loadBookStats(bookIds);
        // 统计窗口配置（批次十一 11.3：收藏/阅读窗口值与窗口天数全部来自配置）
        ContentProperties.Window windowConfig = contentProperties.getStat().getWindow();
        // 窗口行：按书籍ID集合一次批量取回 30/90 天两档（任务尚未覆盖时 Map 为空，读侧回退累计值）
        Map<Long, Map<Integer, ContentStatWindow>> windowById = loadWindowStats(
                ContentBizConstant.STAT_WINDOW_TARGET_BOOK, bookIds,
                windowConfig.getShortDays(), windowConfig.getLongDays());

        // 逐本算分并组装条目
        List<FeedSnapshotItem> scored = new ArrayList<>(books.size());
        for (Book book : books) {
            // 统计行缺失（新书尚无统计行）按 0 作为全量兜底值
            BookStat stat = statById.get(book.getId());
            // 收藏数：优先 30 天窗口 → 90 天窗口 → 累计值（窗口行未计算时才回退累计）
            long collectCount = feedScoreSupport.resolveWindowCount(windowById, book.getId(), windowConfig, true,
                    stat == null ? 0L : stat.getCollectCount());
            // 阅读人数：同样按窗口回退链取值
            long readerCount = feedScoreSupport.resolveWindowCount(windowById, book.getId(), windowConfig, false,
                    stat == null ? 0L : stat.getReaderCount());
            // 新鲜度与完结偏好的取值口径不变（create_time 判定新鲜度，偏好命中记 1）
            double finishMatch = book.getStatus() == profile.getPreferredBookStatus() ? 1.0 : 0.0;
            // 统一打分：推荐流和热度榜共用同一套公式，保证分数口径一致
            // （平滑 m/prior 已切到与文章同一组参数）
            double score = feedScoreSupport.computeBookScore(
                    stat, collectCount, readerCount, book.getCreateTime(), finishMatch);
            scored.add(new FeedSnapshotItem().setId(book.getId()).setScore(score));
        }

        // 分数倒序；同分用 ID 倒序做稳定 tie-breaker
        scored.sort(Comparator.comparingDouble(FeedSnapshotItem::getScore).reversed()
                .thenComparing(FeedSnapshotItem::getId));
        // 新书 7 天保底曝光：即使分数低也保证进入前排配额
        Map<Long, Book> bookById = books.stream()
                .collect(Collectors.toMap(Book::getId, book -> book, (left, right) -> left));
        return applyNewBookGuarantee(scored, bookById, targetSize,
                stream.getNewBookQuota(), stream.getNewBookDays());
    }

    /**
     * 书籍页切片：按偏移取一页 → 展示前门控回查 → 三项计数回填 → 标记曝光
     *
     * @param snapshot    快照
     * @param cursorState 游标状态
     * @param pageSize    页大小
     * @param identity    身份标识
     * @param stream      推荐流配置
     * @param userId      当前登录用户ID（匿名 null；用于切片时剔除「不感兴趣」单条内容）
     * @return 分页结果
     */
    private FeedPageVO<BookFeedItemVO> sliceBookPage(FeedSnapshot snapshot, CursorState cursorState,
                                                     int pageSize, String identity, ContentProperties.Stream stream,
                                                     Long userId) {
        List<FeedSnapshotItem> all = snapshot.getItems();
        // 批次十一 11.11：旧快照里被「不感兴趣」的书籍在切片阶段直接跳过（作者/分类屏蔽在建池时生效）
        Set<Long> blockedContentIds = feedFeedbackSupport
                .loadExclusions(userId, FeedConstant.STREAM_BOOK).getContentIds();
        // 本页起点（cursor 即已消费条数）
        int from = (int) Math.min(cursorState.getOffset(), all.size());
        // 当日已曝光集合：建池后到切片前可能已被曝光（翻页/并发续池），切片阶段必须再过滤
        Set<Long> exposed = feedSnapshotSupport.loadTodayExposed(FeedConstant.STREAM_BOOK, identity);
        // 扫描指针：从 from 向后找满一页未曝光条目，被跳过的已曝光条目同样计入已消费
        int scanTo = from;
        // 本页书籍 ID（保持快照顺序）
        List<Long> ids = new ArrayList<>(pageSize);
        while (scanTo < all.size() && ids.size() < pageSize) {
            Long bookId = all.get(scanTo).getId();
            scanTo++;
            // 已曝光或已被单条屏蔽的条目直接跳过，游标照常前进
            if (exposed.contains(bookId) || blockedContentIds.contains(bookId)) {
                continue;
            }
            ids.add(bookId);
        }

        // 展示前回查门控：下架/软删书籍直接丢弃（已读降权不在切片阶段剔除）
        Map<Long, Book> visible = loadBooksByIds(ids).stream()
                .collect(Collectors.toMap(Book::getId, book -> book, (left, right) -> left));
        // 批次十一 11.2：本页三个计数改读 book_stat，一次批量查询（缺行按 0）
        // 翻页阶段不再做任何 group by 聚合，单页聚合次数为 0
        Map<Long, BookStat> statById = loadBookStats(ids);
        List<BookFeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(book -> FeedItemAssembler.toBookItem(book, statById.get(book.getId())))
                .toList();

        // 本页实际返回的书籍计入当日曝光（后续切片/下一代快照都会按该集合再过滤）
        feedSnapshotSupport.markExposed(FeedConstant.STREAM_BOOK, identity,
                items.stream().map(BookFeedItemVO::getId).toList());
        // 批次十一 11.6：曝光日聚合按页批量 upsert——作用：把本页返回书籍按「天+用户」汇总落库，供运营统计
        // （书籍流与文章流按 stream 分列统计）
        feedExposureSupport.recordExposure(FeedConstant.STREAM_BOOK, identity,
                items.stream().map(BookFeedItemVO::getId).toList());

        // 是否还有下一页：池满（可续下一代）或本页未到快照末尾
        boolean hasMore = all.size() >= stream.getPoolSize() || scanTo < all.size();
        // 下一页游标：已消费条数（含被曝光跳过的条目）
        String nextCursor = hasMore ? String.valueOf(scanTo) : null;
        return new FeedPageVO<BookFeedItemVO>()
                .setItems(items)
                .setCursor(nextCursor)
                .setSnapshotId(snapshot.getSnapshotId())
                .setHasMore(hasMore)
                .setReset(cursorState.isReset())
                // 建池时的降级标记随页返回
                .setDegraded(snapshot.getDegraded());
    }

    /**
     * 书籍流降级页：实时计算一页（不建池、不落 Redis、不写曝光）
     *
     * @param query       查询入参
     * @param pageSize    页大小
     * @param cursorState    游标状态（仅沿用 reset 语义）
     * @param degradedReason 降级原因标记（FeedConstant.DEGRADED_*，回填响应体供前端识别）
     * @return 一次性首页
     */
    private FeedPageVO<BookFeedItemVO> realtimeBookPage(FeedQuery query, int pageSize, CursorState cursorState,
                                                        String degradedReason) {
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        // 画像（10.5 走 Redis 缓存；Redis 不可用时缓存自动未命中，回源 DB 聚合）
        FeedProfile profile = feedProfileSupport.load(query);
        // 降级只取首页所需样本（页大小的 2 倍）
        int sampleSize = pageSize * 2;
        LambdaQueryWrapper<Book> wrapper = bookGate();
        if (!profile.isColdStart() && !profile.getPreferredCategories().isEmpty()) {
            wrapper.in(Book::getCategory, profile.getPreferredCategories().stream()
                    .map(ArticleCategoryEnum::getName)
                    .toList());
        }
        List<Book> books = bookMapper.selectList(Page.of(1, sampleSize, false), wrapper);
        // 批次十一 11.11：降级实时页同样过滤负反馈名单（内容/作者/分类）
        FeedFeedbackExclusions feedback = feedFeedbackSupport.loadExclusions(
                query.getUserId(), FeedConstant.STREAM_BOOK);
        if (!feedback.isEmpty()) {
            books = books.stream().filter(book ->
                    // 命中任一屏蔽维度即剔除
                    !feedback.getContentIds().contains(book.getId())
                            && !feedback.getAuthorIds().contains(book.getAuthorId())
                            && !feedback.getCategories().contains(book.getCategory())).toList();
        }
        // 降级不建池：按页大小排序后取一页，新书保底同样生效
        List<FeedSnapshotItem> ranked = rankBooks(books, profile, stream, pageSize);

        List<Long> ids = ranked.stream().map(FeedSnapshotItem::getId).toList();
        Map<Long, Book> visible = loadBooksByIds(ids).stream()
                .collect(Collectors.toMap(Book::getId, book -> book, (left, right) -> left));
        // 批次十一 11.2：降级页同样读 book_stat（一次批量查询，缺行按 0，不做聚合）
        Map<Long, BookStat> statById = loadBookStats(ids);
        List<BookFeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(book -> FeedItemAssembler.toBookItem(book, statById.get(book.getId())))
                .toList();

        return new FeedPageVO<BookFeedItemVO>()
                .setItems(items)
                .setCursor(null)
                .setSnapshotId(null)
                .setHasMore(false)
                .setReset(cursorState.isReset())
                // 回填降级原因（redis-down / build-failed），前端据此提示
                .setDegraded(degradedReason);
    }

    /**
     * 书籍流 last-good 降级页：返回上一代成功快照的首页（terminal 页：无游标、不可续刷）
     *
     * <p>Redis 不可用或快照建池失败时优先返回上一代成功内容，避免直接掉实时计算。
     * 本页不写曝光记录：降级页是一次性兜底，且 Redis 很可能不可用。</p>
     *
     * @param snapshot    上一代成功快照
     * @param pageSize    页大小
     * @param cursorState 游标状态（仅沿用 reset 语义）
     * @param userId      当前登录用户ID（匿名 null 时名单为空，不做过滤）
     * @return 一次性首页（degraded=last-good）
     *
     * <p>批次十一 11.11 修复：兜底页同样应用负反馈名单（内容/作者/分类），与文章流口径一致。</p>
     */
    private FeedPageVO<BookFeedItemVO> lastGoodBookPage(FeedSnapshot snapshot, int pageSize,
                                                        CursorState cursorState, Long userId) {
        // 快照条目全集
        List<FeedSnapshotItem> all = snapshot.getItems();
        // 先按负反馈名单过滤整代快照，再取首页
        List<Long> filteredIds = filterBookIdsByFeedback(
                all.stream().map(FeedSnapshotItem::getId).toList(), userId);
        // 只取首页：last-good 不提供续刷游标
        int to = Math.min(pageSize, filteredIds.size());
        List<Long> ids = filteredIds.subList(0, to);

        // 展示前门控仍回查：下架/软删书籍绝不能借降级路径放出
        Map<Long, Book> visible = loadBooksByIds(ids).stream()
                .collect(Collectors.toMap(Book::getId, book -> book, (left, right) -> left));
        // 批次十一 11.2：last-good 页同样读 book_stat（一次批量查询，缺行按 0，不做聚合）
        Map<Long, BookStat> statById = loadBookStats(ids);
        List<BookFeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(book -> FeedItemAssembler.toBookItem(book, statById.get(book.getId())))
                .toList();

        // 降级页无 cursor / 无 snapshotId：前端停止续刷；reset 沿用入参语义
        return new FeedPageVO<BookFeedItemVO>()
                .setItems(items)
                .setCursor(null)
                .setSnapshotId(null)
                .setHasMore(false)
                .setReset(cursorState.isReset())
                .setDegraded(FeedConstant.DEGRADED_LAST_GOOD);
    }

    /**
     * 按负反馈名单过滤书籍 ID（批次十一 11.11 修复，供 last-good 兜底页复用）。
     *
     * <p>书籍分类是字符串列，直接与 {@code feed_feedback.target_key} 存的中文分类名比较；
     * 与文章侧 {@link ArticleCategoryEnum#getName()} 的取值同源（发布时落中文名）。</p>
     *
     * @param ids    待过滤书籍 ID（保持原顺序）
     * @param userId 当前登录用户ID（匿名 null 时名单为空，原样返回）
     * @return 过滤后的书籍 ID
     */
    private List<Long> filterBookIdsByFeedback(List<Long> ids, Long userId) {
        // 空集合无需回查
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        // 当前用户当前流的屏蔽名单
        FeedFeedbackExclusions feedback = feedFeedbackSupport.loadExclusions(userId, FeedConstant.STREAM_BOOK);
        // 空名单直接原样返回
        if (feedback.isEmpty()) {
            return ids;
        }
        // 一次批量回查作者与分类
        Map<Long, Book> bookById = loadBooksByIds(ids).stream()
                .collect(Collectors.toMap(Book::getId, book -> book, (left, right) -> left));
        return ids.stream().filter(id -> {
            // 回查不到的书籍交给后续门控丢弃
            Book book = bookById.get(id);
            if (book == null) {
                return true;
            }
            // 命中内容/作者/分类任一维度即剔除
            return !feedback.getContentIds().contains(id)
                    && !feedback.getAuthorIds().contains(book.getAuthorId())
                    && !(book.getCategory() != null && feedback.getCategories().contains(book.getCategory()));
        }).toList();
    }

    // ==================== 召回与门控 ====================

    /**
     * 文章门控查询骨架（批次十需求条款 6）：可推荐分区 + 已发布 + 公开 + 未软删，排除正文大字段
     *
     * @return 新的查询条件（每次调用返回独立实例，便于追加个性化条件）
     */
    private LambdaQueryWrapper<Article> articleGate() {
        return new LambdaQueryWrapper<Article>()
                .in(Article::getZone, FeedConstant.ARTICLE_RECOMMENDABLE_ZONES)
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .select(Article.class, field -> !Article.COLUMN_CONTENT_HTML.equals(field.getColumn()))
                .orderByDesc(Article::getCreateTime)
                .orderByDesc(Article::getId);
    }

    /**
     * 书籍门控查询骨架：状态 ∈ {连载中, 已完结} + 未软删（下架永不进流）
     *
     * @return 新的查询条件
     */
    private LambdaQueryWrapper<Book> bookGate() {
        return new LambdaQueryWrapper<Book>()
                .in(Book::getStatus, FeedConstant.BOOK_RECOMMENDABLE_STATUSES)
                .eq(Book::getDelFlag, DelFlag.NOT_DELETED)
                .orderByDesc(Book::getCreateTime)
                .orderByDesc(Book::getId);
    }

    /**
     * 按 ID 批量回查可推荐文章（展示前门控，兼作召回实体加载）
     *
     * @param ids 文章 ID 集合（可为空）
     * @return 通过门控的文章列表（不在集合中的 ID 表示已失效）
     */
    private List<Article> loadArticlesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .in(Article::getId, ids)
                .in(Article::getZone, FeedConstant.ARTICLE_RECOMMENDABLE_ZONES)
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .select(Article.class, field -> !Article.COLUMN_CONTENT_HTML.equals(field.getColumn()));
        return articleMapper.selectList(wrapper);
    }

    /**
     * 按 ID 批量回查可推荐书籍（展示前门控）
     *
     * @param ids 书籍 ID 集合（可为空）
     * @return 通过门控的书籍列表
     */
    private List<Book> loadBooksByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        return bookMapper.selectList(new LambdaQueryWrapper<Book>()
                .in(Book::getId, ids)
                .in(Book::getStatus, FeedConstant.BOOK_RECOMMENDABLE_STATUSES)
                .eq(Book::getDelFlag, DelFlag.NOT_DELETED));
    }

    /**
     * 按 ID 集合批量读取文章统计行（批次十一 11.2）
     *
     * <p>用主键 IN 查询一次取回，禁止逐条查（排序池最多几百条，一次 IN 足够）；
     * 统计行缺失的 ID 不会出现在返回 Map 中，调用方按 0 处理。</p>
     *
     * @param ids 文章 ID 集合（可为空）
     * @return 文章ID → 统计行
     */
    private Map<Long, ArticleStat> loadArticleStats(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashMap<>();
        }
        // selectBatchIds 走 article_stat 主键 IN 查询，一次取回全部统计行
        return articleStatMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ArticleStat::getArticleId, stat -> stat, (left, right) -> left));
    }

    /**
     * 按 ID 集合批量读取书籍统计行（批次十一 11.2）
     *
     * <p>用主键 IN 查询一次取回，禁止逐条查；统计行缺失的 ID 不会出现在返回 Map 中，
     * 调用方按 0 处理。</p>
     *
     * @param ids 书籍 ID 集合（可为空）
     * @return 书籍ID → 统计行
     */
    private Map<Long, BookStat> loadBookStats(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new HashMap<>();
        }
        // selectBatchIds 走 book_stat 主键 IN 查询，一次取回全部统计行
        return bookStatMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(BookStat::getBookId, stat -> stat, (left, right) -> left));
    }

    // ==================== 排序辅助 ====================

    /**
     * 探索位：保留高分头部，从剩余候选中按会话种子随机抽取低分内容插入后半段
     *
     * <p>随机只发生在内存（{@link Random} 固定种子），不使用 SQL 随机函数。</p>
     *
     * 探索位`applyExploration` ：
     * 如果永远只推最高分的内容，用户会看到“信息茧房”，新内容也永远没机会。
     * 做法是：池子的前 70% 保留严格按分数排的头部，剩下 30% 从低分候选里 用会话种子随机抽取 混入尾部。
     * 随机用固定种子的 Java`Random` （不用 SQL 随机函数），所以同一会话内翻页不会跳变。冷启动用户探索比例更高（默认 30%），因为对他一无所知，更需要多试试不同内容。
     * 
     * @param scored     已按分数倒序的候选
     * @param targetSize 目标池大小
     * @param ratio      探索位比例
     * @param seed       会话种子
     * @return 合并探索位后的列表（长度不超过 targetSize）
     */
    private List<FeedSnapshotItem> applyExploration(List<FeedSnapshotItem> scored, int targetSize,
                                                    double ratio, long seed) {
        // 目标池大小不能超过候选总量
        int poolSize = Math.min(targetSize, scored.size());
        if (poolSize <= 0) {
            return new ArrayList<>();
        }
        int exploreCount = (int) Math.floor(poolSize * Math.max(ratio, 0.0));
        if (exploreCount <= 0) {
            return new ArrayList<>(scored.subList(0, poolSize));
        }
        // 探索位不能占满池子，至少保留 1 条按分数排序
        exploreCount = Math.min(exploreCount, poolSize - 1);
        int exploitCount = poolSize - exploreCount;

        // 头部按分数直接入池
        // 前 70%（探索比例）按分数严格排序，是"利用位"
        List<FeedSnapshotItem> exploit = scored.subList(0, exploitCount);
        
        // 剩余候选里随机抽探索位（同一种子下结果稳定，跨请求不跳变）
        // 剩余低分候选里随机抽探索位（同一种子下结果稳定，跨请求不跳变）
        List<FeedSnapshotItem> remainder = new ArrayList<>(scored.subList(exploitCount, scored.size()));
        
        // 种子的唯一用途 ：作为`new Random(seed)` 的初始值去洗牌（shuffle）。用种子初始化的随机数有个数学性质—— 只要种子相同、输入列表相同，洗出来的顺序就完全相同 （伪随机，可复现）。于是：
        //- 同一用户、同一会话内，即使池子因为某种原因重建（快照被裁剪、并发请求各建一次），探索位 选中的永远是同一批文章 ；
        // - 换个会话（种子变了），低分候选里被抽出来混入的内容就换了一批——用户“隔段时间刷新看到不一样的东西”就是这么来的
        Random random = new Random(seed);
        Collections.shuffle(remainder, random);
        List<FeedSnapshotItem> explore = remainder.subList(0, Math.min(exploreCount, remainder.size()));

        // 前 70% 保留高分顺序，后段与探索位混合打乱，避免探索内容永远沉底看不到
        int stableEnd = Math.max(1, (int) Math.floor(exploit.size() * 0.7));
        List<FeedSnapshotItem> merged = new ArrayList<>(poolSize);
        merged.addAll(exploit.subList(0, stableEnd));
        List<FeedSnapshotItem> tail = new ArrayList<>(exploit.subList(stableEnd, exploit.size()));
        tail.addAll(explore);
        Collections.shuffle(tail, random);
        merged.addAll(tail);
        return merged;
    }

    /**
     * 打散：同作者连续不超过上限、同分类连续不超过上限、两分区尽量交替出现
     *
     * 打散`applyDiversity` ：
     * 防止“连着 5 篇都是同一个作者/同一个分类”。
     * 贪心算法：每次从剩余候选里挑第一条不违反约束的最高分条目（同作者连续不超 N 条、同分类连续不超 N 条、两个分区尽量交替）；候选太少全违反时放宽约束，避免死循环。
     *
     * 在尽量保留推荐分数的前提下，通过贪心算法逐条选择内容，强制限制同作者、同分类的连续出现次数，并尽量让两个分区交替出现；当候选不足时自动放宽约束，确保推荐流既多样又不会卡死。
     *将推荐流快照中的内容，“有约束的重新排序”，防止同作者、同分类、同分区的内容扎堆出现
     *
     * <p>贪心选择：每次从剩余候选中找第一条不违反连续约束的最高分条目；  (“候选内容太少，怎么选都会违反规则，那就别死磕规则了，直接选当前分数最高的那条。”）
     * 若全部违反（候选太少）则放宽约束取最高分，避免死循环。</p>
     *
     * @param ordered     待打散的有序列表
     * @param articles    候选文章映射（取作者/分类/分区）
     * @param maxAuthor   同作者最大连续条数
     * @param maxCategory 同分类最大连续条数
     * @return 打散后的列表
     */
    private List<FeedSnapshotItem> applyDiversity(List<FeedSnapshotItem> ordered, Map<Long, Article> articles,
                                                  int maxAuthor, int maxCategory) {
        //1️⃣ 边界判断
        if (ordered.size() <= 1) {
            return ordered;
        }

        //2️⃣ 初始化候选集合与结果集合
        List<FeedSnapshotItem> remaining = new ArrayList<>(ordered);
        List<FeedSnapshotItem> result = new ArrayList<>(ordered.size());

        //3️⃣ 初始化“上一条”的状态信息
        // 上一条的作者/分类/分区与连续次数
        Long lastAuthorId = null;
        ArticleCategoryEnum lastCategory = null;
        ZoneEnum lastZone = null;
        int authorRun = 0;
        int categoryRun = 0;

        //4️⃣ 主循环：不断从候选集中挑选内容
        while (!remaining.isEmpty()) {

            //5️⃣ 统计当前剩余内容中“分区数量”
            // 当前剩余候选里两个分区的数量（用于判断能否交替分区）
            long normalCount = remaining.stream()
                    .filter(item -> articles.get(item.getId()) != null
                            && articles.get(item.getId()).getZone() == ZoneEnum.NORMAL)
                    .count();
            long writerCount = remaining.size() - normalCount;

            //6️⃣ 遍历候选，寻找第一条“不违反约束”的内容
            FeedSnapshotItem chosen = null;
            for (FeedSnapshotItem candidate : remaining) {
                Article article = articles.get(candidate.getId());
                Long authorId = article == null ? null : article.getAuthorId();
                ArticleCategoryEnum category = article == null ? null : article.getCategory();
                ZoneEnum zone = article == null ? null : article.getZone();
                // 同作者连续超限  ✅ 约束 1：同作者连续不超过 maxAuthor
                boolean authorViolation = authorId != null && authorId.equals(lastAuthorId) && authorRun >= maxAuthor;
                // 同分类连续超限  ✅ 约束 2：同分类连续不超过 maxCategory
                boolean categoryViolation = category != null && category.equals(lastCategory) && categoryRun >= maxCategory;
                // 分区连续：另一分区仍有候选时优先交替（zone 1:1 的软约束）    ✅ 约束 3：分区尽量交替（软约束）
                boolean zoneViolation = zone != null && zone == lastZone
                        && ((zone == ZoneEnum.NORMAL && writerCount > 0) || (zone == ZoneEnum.WRITER && normalCount > 0));

            //7️⃣ 选择策略
                //✅ 情况一：找到不违反约束的候选
                if (!authorViolation && !categoryViolation && !zoneViolation) {
                    chosen = candidate;
                    break;
                }
            }

                //✅ 情况二：所有候选都违反约束（兜底策略）
            // 全部违反约束：放宽取当前最高分（remaining 首条即最高分）
            if (chosen == null) {
                chosen = remaining.get(0);
            }
            remaining.remove(chosen);

            //8️⃣ 更新连续状态
            // 更新连续计数：与上一条相同则累加，不同则重置为 1
            Article chosenArticle = articles.get(chosen.getId());
            Long chosenAuthor = chosenArticle == null ? null : chosenArticle.getAuthorId();
            ArticleCategoryEnum chosenCategory = chosenArticle == null ? null : chosenArticle.getCategory();
            ZoneEnum chosenZone = chosenArticle == null ? null : chosenArticle.getZone();
            authorRun = chosenAuthor != null && chosenAuthor.equals(lastAuthorId) ? authorRun + 1 : 1;
            categoryRun = chosenCategory != null && chosenCategory == lastCategory ? categoryRun + 1 : 1;
            lastAuthorId = chosenAuthor;
            lastCategory = chosenCategory;
            lastZone = chosenZone;

            //9️⃣ 输出最终结果
            result.add(chosen);
        }
        return result;
    }

    /**
     * 新书保底曝光：7 天内创建的书籍即使分数低也保证出现在前排配额位置
     *
     * @param ranked     已按分数倒序的书籍条目
     * @param books      书籍映射
     * @param targetSize 目标池大小
     * @param quota      保底配额（前排最多提前多少本新书）
     * @param newBookDays 新书窗口（天）
     * @return 调整后的池列表（长度不超过 targetSize）
     */
    private List<FeedSnapshotItem> applyNewBookGuarantee(List<FeedSnapshotItem> ranked, Map<Long, Book> books,
                                                         int targetSize, int quota, int newBookDays) {
        // 排序后先截取目标池
        List<FeedSnapshotItem> pool = new ArrayList<>(ranked.subList(0, Math.min(targetSize, ranked.size())));
        if (quota <= 0 || pool.isEmpty()) {
            return pool;
        }
        // 新书判定阈值
        LocalDateTime threshold = LocalDateTime.now().minusDays(Math.max(newBookDays, 1));
        // 按现有分数序取出配额内新书（全量候选里找，保证低分新书也能被捞到）
        List<FeedSnapshotItem> newBookItems = ranked.stream()
                .filter(item -> isNewBook(books.get(item.getId()), threshold))
                .limit(quota)
                .toList();
        if (newBookItems.isEmpty()) {
            return pool;
        }
        // 插入位置：前两条留给全局最高分内容，之后每 3 条插一本新书
        int insertPosition = 2;
        for (FeedSnapshotItem item : newBookItems) {
            if (pool.contains(item)) {
                // 已在池内但位置靠后：提前到保底位置
                int index = pool.indexOf(item);
                if (index > insertPosition) {
                    pool.remove(index);
                    pool.add(Math.min(insertPosition, pool.size()), item);
                }
            } else {
                // 不在池内：插入保底位置，超出池容量时挤掉末位
                pool.add(Math.min(insertPosition, pool.size()), item);
                if (pool.size() > targetSize) {
                    pool.remove(pool.size() - 1);
                }
            }
            insertPosition += 3;
        }
        return pool;
    }

    /**
     * 判断是否为新书（创建时间在窗口内）
     *
     * @param book      书籍实体
     * @param threshold 时间阈值
     * @return true=新书
     */
    private boolean isNewBook(Book book, LocalDateTime threshold) {
        return book != null && book.getCreateTime() != null && !book.getCreateTime().isBefore(threshold);
    }

    /**
     * 按目标 ID 集合批量读取窗口统计行（批次十一 11.3）
     *
     * <p>一次 IN 查询取回短/长两档窗口，禁止逐条查；窗口任务尚未覆盖的目标不会出现在 Map 中，
     * 调用方按回退链取累计值。</p>
     *
     * @param targetType 目标类型（ContentBizConstant.STAT_WINDOW_TARGET_*）
     * @param ids        目标 ID 集合（可为空）
     * @param shortDays  短窗口天数
     * @param longDays   长窗口天数
     * @return 目标ID → (窗口天数 → 窗口行)
     */
    private Map<Long, Map<Integer, ContentStatWindow>> loadWindowStats(int targetType, Collection<Long> ids,
                                                                       int shortDays, int longDays) {
        // 空集合不发查询（防 IN () 语法错误）
        if (ids == null || ids.isEmpty()) {
            return new HashMap<>();
        }
        // 窗口天数去重：short 与 long 配成相同值时只查一档
        List<Integer> windowDays = shortDays == longDays
                ? List.of(shortDays)
                : List.of(shortDays, longDays);
        // 一次批量查询覆盖整个候选池
        List<ContentStatWindow> rows = contentStatWindowMapper.selectByTargets(targetType, ids, windowDays);
        Map<Long, Map<Integer, ContentStatWindow>> result = new HashMap<>(rows.size());
        for (ContentStatWindow row : rows) {
            result.computeIfAbsent(row.getTargetId(), key -> new HashMap<>())
                    .put(row.getWindowDays(), row);
        }
        return result;
    }

    // ==================== 游标与配置 ====================

    /**
     * 解析分页大小：入参为空取配置值，非法/超上限统一收口
     *
     * @param requested 客户端传入的页大小，可为空
     * @return 实际使用的页大小
     */
    private int resolvePageSize(Integer requested) {
        // 配置默认页大小（litisle.content.feed.page-size，默认 20）
        int configPageSize = contentProperties.getFeed().getPageSize();
        if (requested == null || requested < 1) {
            return configPageSize;
        }
        return Math.min(requested, FeedConstant.MAX_PAGE_SIZE);
    }

    /**
     * 解析游标（cursor 即快照内已消费条数，from 恒从快照头部计）
     *
     * 它是推荐流“游标分页”的入口解析器，负责把前端传来的 cursor 字符串，解析成“从快照第几条开始取”的偏移量，并处理各种非法情况。
     *
     * <p>不做页大小对齐校验：cursor 记录的是绝对消费条数，页大小中途变化也连续；
     * 非法/负数游标按首页处理并标记 reset。</p>
     *
     * @param query 查询入参
     * @return 游标状态
     */
    private CursorState resolveCursor(FeedQuery query) {
        // 偏移量：默认从快照头开始
        long offset = FeedConstant.CURSOR_START_OFFSET;
        // 是否需要前端重置列表（快照失效或游标非法时为 true）
        boolean reset = false;
        if (StringUtils.hasText(query.getCursor())) {
            try {
                offset = Long.parseLong(query.getCursor());
            } catch (NumberFormatException exception) {
                // 非数字游标按首页处理并标记重置，避免把非法入参带进切片
                reset = true;
                offset = FeedConstant.CURSOR_START_OFFSET;
            }
        }
        // 负偏移非法：重置到头部
        if (offset < 0) {
            reset = true;
            offset = FeedConstant.CURSOR_START_OFFSET;
        }
        return new CursorState(offset, reset);
    }

    /**
     * 校验召回模式配置：本期只实现 db，配置为 es 时告警并回落 db
     * <p>ES 分支的触发条件与切换阈值登记在 next_update 批次十 10.9，不在此处静默跳过</p>
     */
    private void warnIfEsRecallConfigured() {
        // 配置中的召回模式（db|es）
        String recall = contentProperties.getFeed().getRecall();
        if (FeedConstant.RECALL_ES.equalsIgnoreCase(recall)) {
            log.warn("litisle.content.feed.recall=es 尚未实现（P1），本次按 db 四路召回执行；切换阈值见 next_update 批次十");
        }
    }

    /**
     * 降级留痕统一出口（批次十 10.6 + 指标收尾）
     *
     * <p>所有降级分支（Redis 不可用 / 快照建池失败 / last-good 兜底 / 热度榜为空）
     * 都经本方法打一条 WARN 并回填统一降级标记，便于运维按 reason 聚合统计。</p>
     *
     * <p><b>指标单入口</b>：降级 Counter {@code litisle_content_feed_degraded_total}
     * 只在本方法内打点（标签 stream/reason），任何降级分支都必经此处，不存在绕过统计的路径。</p>
     *
     * @param reason     降级原因（FeedConstant.DEGRADED_*）
     * @param streamType 流类型（article / book）
     * @param scope      快照作用域
     * @param detail     补充说明（可为空）
     */
    private void logDegrade(String reason, String streamType, String scope, String detail) {
        log.warn("推荐流降级：reason={}, stream={}, scope={}, detail={}", reason, streamType, scope, detail);
        // 降级指标：与日志同参数打点，保证「日志可见的降级」与「指标统计的降级」口径一致
        contentMetrics.recordFeedDegraded(streamType, reason);
    }

    /**
     * 游标解析结果（偏移 + 是否需要重置）
     */
    private static class CursorState {

        /** 本页起始偏移（快照内已消费条数） */
        private final long offset;

        /** 是否要求前端清空列表重新渲染（快照失效/游标非法时为 true） */
        private final boolean reset;

        /**
         * 构造游标状态
         *
         * @param offset 起始偏移
         * @param reset  是否需重置
         */
        private CursorState(long offset, boolean reset) {
            this.offset = offset;
            this.reset = reset;
        }

        /**
         * 获取起始偏移
         *
         * @return 偏移量
         */
        private long getOffset() {
            return offset;
        }

        /**
         * 是否需要前端重置
         *
         * @return true 表示需重置
         */
        private boolean isReset() {
            return reset;
        }
    }
}
