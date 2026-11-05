package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.domain.dto.FeedProfile;
import com.litisle.litislecontent.domain.dto.FeedQuery;
import com.litisle.litislecontent.domain.dto.FeedSnapshot;
import com.litisle.litislecontent.domain.dto.FeedSnapshotItem;
import com.litisle.litislecontent.domain.dto.IdCountDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.Book;
import com.litisle.litislecontent.domain.vo.BookFeedItemVO;
import com.litisle.litislecontent.domain.vo.FeedItemVO;
import com.litisle.litislecontent.domain.vo.FeedPageVO;
import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.BookChapterMapper;
import com.litisle.litislecontent.mapper.BookMapper;
import com.litisle.litislecontent.mapper.FavoriteMapper;
import com.litisle.litislecontent.mapper.ReadingHistoryMapper;
import com.litisle.litislecontent.service.FeedStreamService;
import com.litisle.litislecontent.service.support.FeedHotRankSupport;
import com.litisle.litislecontent.service.support.FeedProfileSupport;
import com.litisle.litislecontent.service.support.FeedSnapshotSupport;
import com.litisle.litislecontent.support.FeedItemAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
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
 * <p>文章流：四路召回（最热/最新/常看分区/常看分类）→ 四项融合打分 → 探索位与打散 →
 * 300 条快照落 Redis → cursor 偏移切片 → 翻到底自动生成下一代快照。
 * 当日曝光用 Redis Set 硬去重；已读只降权不剔除；Redis 不可用时降级为实时计算一页。</p>
 *
 * <p>书籍流：按用户分类偏好召回（不参与 zone）→ 收藏/阅读/新鲜度/完结偏好四权重
 * （两个计数先 log1p 归一化）→ 新书 7 天保底曝光 → 同一套快照分页。</p>
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

    /** 收藏 Mapper（书籍收藏数聚合） */
    private final FavoriteMapper favoriteMapper;

    /** 阅读历史 Mapper（书籍阅读人数聚合） */
    private final ReadingHistoryMapper readingHistoryMapper;

    /** 章节 Mapper（书籍章节数聚合） */
    private final BookChapterMapper bookChapterMapper;

    /** 热度榜支撑（最热召回复用 topArticleIds，打分复用 computeScore） */
    private final FeedHotRankSupport feedHotRankSupport;

    /** 用户画像聚合（reading_history 分区/分类偏好） */
    private final FeedProfileSupport feedProfileSupport;

    /** 快照/曝光/种子存储 */
    private final FeedSnapshotSupport feedSnapshotSupport;

    /** 推荐流配置（分页、召回池、权重、TTL、配额） */
    private final ContentProperties contentProperties;

    // ==================== 文章流（10.3） ====================

    /**
     * 文章流：按游标返回一页可推荐文章
     *
     * @param query 查询入参（身份/游标/快照/页大小/分区筛选）
     * @return 游标分页结果
     */
    @Override
    public FeedPageVO<FeedItemVO> feedArticles(FeedQuery query) {
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

        // 步骤4：Redis 不可用 → 降级：实时计算一页后直接返回，不落池（不在降级里算 300 条）
        if (!feedSnapshotSupport.isRedisAvailable()) {
            log.warn("推荐流降级（Redis 不可用，实时计算首页不落池）：stream=article, scope={}", scope);
            return realtimeArticlePage(query, pageSize, cursorState);
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
                // 建池失败（Redis 写入异常）：与不可用同口径降级
                log.warn("推荐流快照生成失败（降级为实时首页）：stream=article, scope={}", scope);
                return realtimeArticlePage(query, pageSize, cursorState);
            }
            // 新快照从头部开始切片（reset 语义沿用：失效时为 true，续池时为 false）
            cursorState = new CursorState(FeedConstant.CURSOR_START_OFFSET, cursorState.isReset());
        }

        // 步骤7：切片 + 展示前回查 + 曝光写入
        return sliceArticlePage(snapshot, cursorState, pageSize, identity, stream);
    }

    /**
     * 建文章快照：四路召回 → 去重/曝光过滤 → 打分排序 → 探索位与打散 → 落 Redis
     *
     * @param query    查询入参
     * @param identity 身份标识
     * @param scope    快照作用域
     * @param stream   推荐流配置
     * @return 快照对象；Redis 写入失败返回 null（调用方降级）
     */
    private FeedSnapshot buildArticleSnapshot(FeedQuery query, String identity, String scope,
                                              ContentProperties.Stream stream) {
        // 用户画像（匿名/无行为数据 → coldStart=true，两路个性化退化全局）
        FeedProfile profile = feedProfileSupport.load(query.getUserId());
        // 每路召回条数
        int perPath = stream.getRecallLimitPerPath();
        // 候选池（LinkedHashMap 去重，同一文章多路命中只保留一份）
        Map<Long, Article> candidates = new LinkedHashMap<>();

        // 路1：最热（复用现有热度榜 ZSet，不另建热度计算）
        List<Long> hotIds = feedHotRankSupport.topArticleIds(1, perPath);
        if (!hotIds.isEmpty()) {
            loadArticlesByIds(hotIds).forEach(article -> candidates.putIfAbsent(article.getId(), article));
        }

        // 路2：最新（按 create_time 倒序，走 idx_create_time）
        articleMapper.selectList(Page.of(1, perPath, false), articleGate())
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

        // 当日曝光硬去重：已曝光的不再进池（已读降权走打分，不在此剔除）
        Set<Long> exposed = feedSnapshotSupport.loadTodayExposed(FeedConstant.STREAM_ARTICLE, identity);
        if (!exposed.isEmpty()) {
            candidates.keySet().removeAll(exposed);
        }

        // 会话种子（同一快照窗口内稳定，跨会话可变）
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
                .setItems(items);
        feedSnapshotSupport.writeSnapshot(scope, snapshot);
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
        // 热度原始分：直接复用 FeedHotRankSupport 的公式（social 未落地时等价于浏览量+时间衰减）
        Map<Long, Double> rawHeat = new HashMap<>(candidates.size());
        double minHeat = Double.MAX_VALUE;
        double maxHeat = -Double.MAX_VALUE;
        for (Article article : candidates.values()) {
            // 计数可空（历史数据），空值按 0 参与公式
            double heat = feedHotRankSupport.computeScore(
                    article.getViewCnt() == null ? 0L : article.getViewCnt(),
                    article.getLikeCnt() == null ? 0L : article.getLikeCnt(),
                    article.getCommentCnt() == null ? 0L : article.getCommentCnt(),
                    article.getCreateTime() == null ? LocalDateTime.now() : article.getCreateTime());
            rawHeat.put(article.getId(), heat);
            minHeat = Math.min(minHeat, heat);
            maxHeat = Math.max(maxHeat, heat);
        }

        // 逐篇算四项加权分
        List<FeedSnapshotItem> scored = new ArrayList<>(candidates.size());
        for (Article article : candidates.values()) {
            double heatNorm = normalize(rawHeat.get(article.getId()), minHeat, maxHeat);
            double freshScore = freshScore(article.getCreateTime(), stream.getFreshHalfLifeHours());
            // 分区匹配：命中用户常看分区记 1，否则 0（冷启动画像为空集合，自然为 0）
            double zoneMatch = profile.getPreferredZones().contains(article.getZone()) ? 1.0 : 0.0;
            // 分类匹配：命中用户常看分类记 1，否则 0
            double categoryMatch = profile.getPreferredCategories().contains(article.getCategory()) ? 1.0 : 0.0;
            ContentProperties.ArticleWeights weights = stream.getArticleWeights();
            double score = weights.getHeat() * heatNorm
                    + weights.getFresh() * freshScore
                    + weights.getZone() * zoneMatch
                    + weights.getCategory() * categoryMatch;
            // 已读降权（不剔除）：窗口内已读文章扣分，保证长刷仍能看到但排位靠后
            if (profile.getReadArticleIds().contains(article.getId())) {
                score -= stream.getReadPenalty();
            }
            scored.add(new FeedSnapshotItem().setId(article.getId()).setScore(score));
        }

        // 分数倒序；同分用 ID 倒序做稳定 tie-breaker（避免翻页顺序抖动）
        scored.sort(Comparator.comparingDouble(FeedSnapshotItem::getScore).reversed()
                .thenComparing(FeedSnapshotItem::getId));
        // 探索位 + 打散
        List<FeedSnapshotItem> explored = applyExploration(scored, targetSize, stream.getExplorationRatio(), seed);
        return applyDiversity(explored, candidates,
                stream.getMaxConsecutiveSameAuthor(), stream.getMaxConsecutiveSameCategory());
    }

    /**
     * 文章页切片：按偏移取一页 → 展示前门控回查 → 标记当日曝光 → 组装分页结果
     *
     * @param snapshot    快照
     * @param cursorState 游标状态
     * @param pageSize    页大小
     * @param identity    身份标识
     * @param stream      推荐流配置
     * @return 分页结果
     */
    private FeedPageVO<FeedItemVO> sliceArticlePage(FeedSnapshot snapshot, CursorState cursorState,
                                                    int pageSize, String identity, ContentProperties.Stream stream) {
        // 快照条目全集（已按分数与打散规则排好序）
        List<FeedSnapshotItem> all = snapshot.getItems();
        // 本页起点（cursor 即已消费条数，from 恒从快照头部计偏移）
        int from = (int) Math.min(cursorState.getOffset(), all.size());
        // 本页终点（不超过快照长度）
        int to = (int) Math.min(from + pageSize, all.size());

        // 本页对象 ID（保持快照顺序）
        List<Long> ids = all.subList(from, to).stream().map(FeedSnapshotItem::getId).toList();
        // 展示前回查门控：下架/转私密/软删的旧条目直接丢弃，保证不会曝光失效内容
        Map<Long, Article> visible = loadArticlesByIds(ids).stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (left, right) -> left));
        List<FeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(FeedItemAssembler::toArticleItem)
                .toList();

        // 本页实际返回的条目计入当日曝光（下一轮快照会把这些 ID 过滤掉）
        feedSnapshotSupport.markExposed(FeedConstant.STREAM_ARTICLE, identity,
                items.stream().map(FeedItemVO::getArticleId).toList());

        // 是否还有下一页：池满（可续下一代）或本页未到快照末尾
        boolean hasMore = all.size() >= stream.getPoolSize() || to < all.size();
        // 下一页游标：已消费条数（到快照末尾且池未满时为空，前端停止加载）
        String nextCursor = hasMore ? String.valueOf(to) : null;
        return new FeedPageVO<FeedItemVO>()
                .setItems(items)
                .setCursor(nextCursor)
                .setSnapshotId(snapshot.getSnapshotId())
                .setHasMore(hasMore)
                .setReset(cursorState.isReset());
    }

    /**
     * 文章流降级页：实时计算一页（不建 300 条池、不落 Redis、不写曝光）
     *
     * @param query       查询入参
     * @param pageSize    页大小
     * @param cursorState 游标状态（仅沿用 reset 语义）
     * @return 一次性首页（无 cursor / 无 snapshotId，前端停止续刷）
     */
    private FeedPageVO<FeedItemVO> realtimeArticlePage(FeedQuery query, int pageSize, CursorState cursorState) {
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        FeedProfile profile = feedProfileSupport.load(query.getUserId());
        // 降级只取首页所需样本（页大小的 2 倍），不在降级路径里算满池
        int sampleSize = pageSize * 2;
        Map<Long, Article> candidates = new LinkedHashMap<>();

        // 最新一路
        articleMapper.selectList(Page.of(1, sampleSize, false), articleGate())
                .forEach(article -> candidates.putIfAbsent(article.getId(), article));
        // 最热一路（复用热度榜）
        List<Long> hotIds = feedHotRankSupport.topArticleIds(1, sampleSize);
        if (!hotIds.isEmpty()) {
            loadArticlesByIds(hotIds).forEach(article -> candidates.putIfAbsent(article.getId(), article));
        }
        // 分区筛选
        if (query.getZone() != null) {
            candidates.entrySet().removeIf(entry -> entry.getValue().getZone() != query.getZone());
        }

        // 降级没有稳定会话种子：用随机种子打散，保证每次降级内容不完全相同
        List<FeedSnapshotItem> ranked = rankArticles(candidates, profile, stream, pageSize,
                ThreadLocalRandom.current().nextLong());
        List<Long> ids = ranked.stream().map(FeedSnapshotItem::getId).toList();
        Map<Long, Article> visible = loadArticlesByIds(ids).stream()
                .collect(Collectors.toMap(Article::getId, article -> article, (left, right) -> left));
        List<FeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(FeedItemAssembler::toArticleItem)
                .toList();

        // 降级页没有快照：cursor/snapshotId 为空，hasMore=false，前端停止加载
        return new FeedPageVO<FeedItemVO>()
                .setItems(items)
                .setCursor(null)
                .setSnapshotId(null)
                .setHasMore(false)
                .setReset(cursorState.isReset());
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

        // 步骤4：Redis 不可用 → 降级为实时一页
        if (!feedSnapshotSupport.isRedisAvailable()) {
            log.warn("推荐流降级（Redis 不可用，实时计算首页不落池）：stream=book, scope={}", scope);
            return realtimeBookPage(query, pageSize, cursorState);
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
                log.warn("推荐流快照生成失败（降级为实时首页）：stream=book, scope={}", scope);
                return realtimeBookPage(query, pageSize, cursorState);
            }
            cursorState = new CursorState(FeedConstant.CURSOR_START_OFFSET, cursorState.isReset());
        }

        // 步骤7：切片 + 门控回查 + 计数回填 + 曝光写入
        return sliceBookPage(snapshot, cursorState, pageSize, identity, stream);
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
        // 用户画像（分类偏好与完结偏好来源）
        FeedProfile profile = feedProfileSupport.load(query.getUserId());
        // 书籍门控 + 默认排序（新建时间倒序，仅决定入池先后，最终按打分重排）
        LambdaQueryWrapper<Book> wrapper = bookGate();

        // 有分类偏好时按偏好分类召回（书籍不参与 zone；冷启动不限定分类，取全局候选）
        if (!profile.isColdStart() && !profile.getPreferredCategories().isEmpty()) {
            wrapper.in(Book::getCategory, profile.getPreferredCategories().stream()
                    .map(ArticleCategoryEnum::getName)
                    .toList());
        }
        List<Book> books = bookMapper.selectList(Page.of(1, stream.getPoolSize(), false), wrapper);

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
        feedSnapshotSupport.writeSnapshot(scope, snapshot);
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
        // 书籍 ID 集合（三组聚合查询共用）
        List<Long> bookIds = books.stream().map(Book::getId).toList();
        // 三个计数一次查全池，避免逐本查询造成 N+1
        Map<Long, Long> collectCounts = toCountMap(favoriteMapper.countBookCollects(bookIds));
        Map<Long, Long> readerCounts = toCountMap(readingHistoryMapper.countBookReaders(bookIds));

        // 归一化分母：池内最大 log1p 值（为 0 时全池该项记 0）
        long maxCollect = collectCounts.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        long maxReader = readerCounts.values().stream().mapToLong(Long::longValue).max().orElse(0L);

        ContentProperties.BookWeights weights = stream.getBookWeights();
        List<FeedSnapshotItem> scored = new ArrayList<>(books.size());
        for (Book book : books) {
            // 收藏数归一化（log1p 抑制头部数值差异）
            double collectNorm = maxCollect <= 0 ? 0.0
                    : Math.log1p(collectCounts.getOrDefault(book.getId(), 0L)) / Math.log1p(maxCollect);
            // 阅读人数归一化
            double readNorm = maxReader <= 0 ? 0.0
                    : Math.log1p(readerCounts.getOrDefault(book.getId(), 0L)) / Math.log1p(maxReader);
            // 新鲜度：优先用 update_time（书籍无发布时间列），空值回退 create_time
            LocalDateTime freshTime = book.getUpdateTime() != null ? book.getUpdateTime() : book.getCreateTime();
            double freshScore = freshScore(freshTime, stream.getFreshHalfLifeHours());
            // 完结偏好：命中用户偏好状态记 1，否则 0
            double finishMatch = book.getStatus() == profile.getPreferredBookStatus() ? 1.0 : 0.0;
            double score = weights.getCollect() * collectNorm
                    + weights.getRead() * readNorm
                    + weights.getFresh() * freshScore
                    + weights.getFinish() * finishMatch;
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
     * @return 分页结果
     */
    private FeedPageVO<BookFeedItemVO> sliceBookPage(FeedSnapshot snapshot, CursorState cursorState,
                                                     int pageSize, String identity, ContentProperties.Stream stream) {
        List<FeedSnapshotItem> all = snapshot.getItems();
        int from = (int) Math.min(cursorState.getOffset(), all.size());
        int to = (int) Math.min(from + pageSize, all.size());
        List<Long> ids = all.subList(from, to).stream().map(FeedSnapshotItem::getId).toList();

        // 展示前回查门控：下架/软删书籍直接丢弃
        Map<Long, Book> visible = loadBooksByIds(ids).stream()
                .collect(Collectors.toMap(Book::getId, book -> book, (left, right) -> left));
        // 本页三个计数（注意：只对本页 ID 聚合，成本与页大小相关，不随池大小增长）
        Map<Long, Long> chapterCounts = ids.isEmpty() ? Collections.emptyMap()
                : toCountMap(bookChapterMapper.countBookChapters(ids));
        Map<Long, Long> collectCounts = ids.isEmpty() ? Collections.emptyMap()
                : toCountMap(favoriteMapper.countBookCollects(ids));
        Map<Long, Long> readerCounts = ids.isEmpty() ? Collections.emptyMap()
                : toCountMap(readingHistoryMapper.countBookReaders(ids));

        List<BookFeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(book -> FeedItemAssembler.toBookItem(book,
                        toIntegerCount(chapterCounts.get(book.getId())),
                        collectCounts.get(book.getId()),
                        readerCounts.get(book.getId())))
                .toList();

        // 本页实际返回的书籍计入当日曝光
        feedSnapshotSupport.markExposed(FeedConstant.STREAM_BOOK, identity,
                items.stream().map(BookFeedItemVO::getId).toList());

        boolean hasMore = all.size() >= stream.getPoolSize() || to < all.size();
        String nextCursor = hasMore ? String.valueOf(to) : null;
        return new FeedPageVO<BookFeedItemVO>()
                .setItems(items)
                .setCursor(nextCursor)
                .setSnapshotId(snapshot.getSnapshotId())
                .setHasMore(hasMore)
                .setReset(cursorState.isReset());
    }

    /**
     * 书籍流降级页：实时计算一页（不建池、不落 Redis、不写曝光）
     *
     * @param query       查询入参
     * @param pageSize    页大小
     * @param cursorState 游标状态（仅沿用 reset 语义）
     * @return 一次性首页
     */
    private FeedPageVO<BookFeedItemVO> realtimeBookPage(FeedQuery query, int pageSize, CursorState cursorState) {
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        FeedProfile profile = feedProfileSupport.load(query.getUserId());
        // 降级只取首页所需样本（页大小的 2 倍）
        int sampleSize = pageSize * 2;
        LambdaQueryWrapper<Book> wrapper = bookGate();
        if (!profile.isColdStart() && !profile.getPreferredCategories().isEmpty()) {
            wrapper.in(Book::getCategory, profile.getPreferredCategories().stream()
                    .map(ArticleCategoryEnum::getName)
                    .toList());
        }
        List<Book> books = bookMapper.selectList(Page.of(1, sampleSize, false), wrapper);
        // 降级不建池：按页大小排序后取一页，新书保底同样生效
        List<FeedSnapshotItem> ranked = rankBooks(books, profile, stream, pageSize);

        List<Long> ids = ranked.stream().map(FeedSnapshotItem::getId).toList();
        Map<Long, Book> visible = loadBooksByIds(ids).stream()
                .collect(Collectors.toMap(Book::getId, book -> book, (left, right) -> left));
        Map<Long, Long> chapterCounts = ids.isEmpty() ? Collections.emptyMap()
                : toCountMap(bookChapterMapper.countBookChapters(ids));
        Map<Long, Long> collectCounts = ids.isEmpty() ? Collections.emptyMap()
                : toCountMap(favoriteMapper.countBookCollects(ids));
        Map<Long, Long> readerCounts = ids.isEmpty() ? Collections.emptyMap()
                : toCountMap(readingHistoryMapper.countBookReaders(ids));

        List<BookFeedItemVO> items = ids.stream()
                .map(visible::get)
                .filter(Objects::nonNull)
                .map(book -> FeedItemAssembler.toBookItem(book,
                        toIntegerCount(chapterCounts.get(book.getId())),
                        collectCounts.get(book.getId()),
                        readerCounts.get(book.getId())))
                .toList();

        return new FeedPageVO<BookFeedItemVO>()
                .setItems(items)
                .setCursor(null)
                .setSnapshotId(null)
                .setHasMore(false)
                .setReset(cursorState.isReset());
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

    // ==================== 排序辅助 ====================

    /**
     * 探索位：保留高分头部，从剩余候选中按会话种子随机抽取低分内容插入后半段
     *
     * <p>随机只发生在内存（{@link Random} 固定种子），不使用 SQL 随机函数。</p>
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
        List<FeedSnapshotItem> exploit = scored.subList(0, exploitCount);
        // 剩余候选里随机抽探索位（同一种子下结果稳定，跨请求不跳变）
        List<FeedSnapshotItem> remainder = new ArrayList<>(scored.subList(exploitCount, scored.size()));
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
     * <p>贪心选择：每次从剩余候选中找第一条不违反连续约束的最高分条目；
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
        if (ordered.size() <= 1) {
            return ordered;
        }
        List<FeedSnapshotItem> remaining = new ArrayList<>(ordered);
        List<FeedSnapshotItem> result = new ArrayList<>(ordered.size());
        // 上一条的作者/分类/分区与连续次数
        Long lastAuthorId = null;
        ArticleCategoryEnum lastCategory = null;
        ZoneEnum lastZone = null;
        int authorRun = 0;
        int categoryRun = 0;

        while (!remaining.isEmpty()) {
            // 当前剩余候选里两个分区的数量（用于判断能否交替分区）
            long normalCount = remaining.stream()
                    .filter(item -> articles.get(item.getId()) != null
                            && articles.get(item.getId()).getZone() == ZoneEnum.NORMAL)
                    .count();
            long writerCount = remaining.size() - normalCount;

            FeedSnapshotItem chosen = null;
            for (FeedSnapshotItem candidate : remaining) {
                Article article = articles.get(candidate.getId());
                Long authorId = article == null ? null : article.getAuthorId();
                ArticleCategoryEnum category = article == null ? null : article.getCategory();
                ZoneEnum zone = article == null ? null : article.getZone();
                // 同作者连续超限
                boolean authorViolation = authorId != null && authorId.equals(lastAuthorId) && authorRun >= maxAuthor;
                // 同分类连续超限
                boolean categoryViolation = category != null && category.equals(lastCategory) && categoryRun >= maxCategory;
                // 分区连续：另一分区仍有候选时优先交替（zone 1:1 的软约束）
                boolean zoneViolation = zone != null && zone == lastZone
                        && ((zone == ZoneEnum.NORMAL && writerCount > 0) || (zone == ZoneEnum.WRITER && normalCount > 0));
                if (!authorViolation && !categoryViolation && !zoneViolation) {
                    chosen = candidate;
                    break;
                }
            }
            // 全部违反约束：放宽取当前最高分（remaining 首条即最高分）
            if (chosen == null) {
                chosen = remaining.get(0);
            }
            remaining.remove(chosen);

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
     * 新鲜度分：按半衰期指数衰减（发布时间越近分越高，24 小时后衰减到 0.5）
     *
     * @param publishTime   发布时间（可空）
     * @param halfLifeHours 半衰期（小时）
     * @return 0~1 的新鲜度分
     */
    private double freshScore(LocalDateTime publishTime, double halfLifeHours) {
        // 时间缺失时给中性分，避免空指针并把老数据一律判为新
        if (publishTime == null || halfLifeHours <= 0) {
            return 0.5;
        }
        long hours = Math.max(Duration.between(publishTime, LocalDateTime.now()).toHours(), 0);
        return Math.pow(0.5, hours / halfLifeHours);
    }

    /**
     * 极值归一化（热度分在不同时间量级差异大，先压到 0~1 再参与加权）
     *
     * @param value 原始值
     * @param min   池内最小值
     * @param max   池内最大值
     * @return 0~1 归一化值；池内全同分时返回 0.5
     */
    private double normalize(double value, double min, double max) {
        // 全池同分（或只有一条）：给中性分，避免除零
        if (max <= min) {
            return 0.5;
        }
        return (value - min) / (max - min);
    }

    /**
     * 聚合计数结果转 Map
     *
     * @param rows 聚合行
     * @return ID→计数映射
     */
    private Map<Long, Long> toCountMap(List<IdCountDTO> rows) {
        Map<Long, Long> counts = new HashMap<>(rows.size());
        for (IdCountDTO row : rows) {
            if (row.getId() != null) {
                counts.put(row.getId(), row.getCnt());
            }
        }
        return counts;
    }

    /**
     * 章节数 Long 转 Integer（VO 字段为 Integer；查不到保持 null）
     *
     * @param count 聚合计数
     * @return Integer 计数或 null
     */
    private Integer toIntegerCount(Long count) {
        return count == null ? null : Math.toIntExact(count);
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
