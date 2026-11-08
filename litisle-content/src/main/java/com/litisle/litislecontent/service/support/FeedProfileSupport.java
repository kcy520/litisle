package com.litisle.litislecontent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.domain.dto.FeedProfile;
import com.litisle.litislecontent.domain.dto.FeedProfileCache;
import com.litisle.litislecontent.domain.dto.FeedQuery;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.Book;
import com.litisle.litislecontent.domain.entity.ReadingHistory;
import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.BookStatusEnum;
import com.litisle.litislecontent.enums.ReadingTargetTypeEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.BookMapper;
import com.litisle.litislecontent.mapper.ReadingHistoryMapper;
import com.litisle.litislecontent.metrics.ContentMetrics;
import com.litisle.litislecontent.service.impl.FeedStreamServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.cache.CacheProperties.Redis;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 推荐画像聚合与缓存（批次十 10.3 建骨架 / 10.5 补 Redis 缓存与冷启动口径）
 *
 * <p>P0 边界（对齐 next_update 已确认决策）：
 * <ul>
 *   <li>画像维度只有「常看分区 + 常看分类」两维，数据源只有 reading_history（近 readWindowDays 天）；</li>
 *   <li>不读 article_tag（标签偏好属 P1，{@link FeedProfile#getPreferredTags()} 仅留结构占位）；</li>
 *   <li>不读收藏、不做「登录后匿名画像归并」（本轮范围外）；</li>
 *   <li>匿名用户没有 reading_history，恒为冷启动画像。</li>
 * </ul>
 *
 * <p>缓存（10.5）：
 * <ul>
 *   <li>键按身份隔离：登录 {@code content:feed:profile:u{userId}}、匿名 {@code content:feed:profile:a{anonId}}，绝不混用；</li>
 *   <li>未命中才回源聚合，命中直接返回；TTL 由 stream.profile-cache-ttl-seconds 控制；</li>
 *   <li>缓存载体用 {@link FeedProfileCache}（int/string 表示枚举），避免枚举 @JsonValue 反序列化歧义。</li>
 * </ul>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedProfileSupport {

    /** 分区偏好取前几名（分区仅两个取值，取 1 个即可表达「常看」） */
    private static final int TOP_ZONE_COUNT = 1;

    /** 分类偏好取前几名（受控封闭集共 4 个分类，取前 2 个避免过度收窄） */
    private static final int TOP_CATEGORY_COUNT = 2;

    /** 已读书籍样本条数（判断「完结偏好」只需少量最近样本） */
    private static final int BOOK_SAMPLE_SIZE = 50;

    /** 阅读历史 Mapper（画像主数据源） */
    private final ReadingHistoryMapper readingHistoryMapper;

    /** 文章 Mapper（按已读文章 ID 反查分区/分类） */
    private final ArticleMapper articleMapper;

    /** 书籍 Mapper（按已读书籍 ID 反查状态，判断完结偏好） */
    private final BookMapper bookMapper;

    /** 推荐流配置（画像样本条数/已读窗口/缓存 TTL 外置 yml） */
    private final ContentProperties contentProperties;

    /** Redis 工具（画像缓存读写；封装内部吞异常，失败时按未命中回源聚合） */
    private final RedisUtil redisUtil;

    /** 观测指标（画像缓存命中/未命中打点；MeterRegistry 未装配时静默空转） */
    private final ContentMetrics contentMetrics;


/// ### 聚合流程（FeedProfileSupport.java:aggregate）
// 1. 取样本 ：查近`readWindowDays` 天的文章阅读历史（按更新时间倒序，限量`profileSampleSize` 条，走分页插件）；
// 2. 反查内容 ：按已读文章 ID 批量反查分区/分类（只取三列，避开正文大字段）；
// 3. 计数取 Top-N ：统计各分区/分类的阅读次数，分区取第 1 名、分类取前 2 名；
// 4. 冷启动判定 ：窗口内 没有任何文章阅读记录 ⇒`coldStart=true` ，两路个性化召回全部跳过；
// 5. 完结偏好 （ resolvePreferredBookStatus ）：取最近 50 条书籍阅读历史反查书籍状态， 完结占比 > 50% 判定为偏好完结，否则偏好连载；无样本默认完结。
// ###  缓存策略
// 画像按身份隔离缓存到 Redis：登录`content:feed:profile:u{userId}` 、匿名`content:feed:profile:a{anonId}` ，绝不混用。TTL 由`profile-cache-ttl-seconds` 控制， 阅读行为不主动失效 （画像只影响排序不影响可见性，滞后 ≤TTL 可接受）。缓存载体用`FeedProfileCache` （int/string 表示枚举）避免枚举反序列化歧义。

/// ## 偏好如何生效：FeedStreamServiceImpl 的两个环节
//    文章偏好和书籍偏好是「分开算的、独立存储的、互不干扰的两套维度」，不是统一综合成一个画像。并且同一画像对象内的两类独立维度
// 以文章流为例（ FeedStreamServiceImpl.java:L323-L379 ）：
// ① 召回阶段（个性化扩池） ：非冷启动时，在“最热 + 最新”两路全局召回之外，增加“常看分区”和“常看分类”两路召回（各取`perPath` 条，`putIfAbsent` 去重入池）。冷启动则跳过，退化为全局热门+最新。
// ② 打分阶段（个性化排序） ：....

/// ## 什么是“召回”？
// 推荐流生成通常分两个阶段，这是工业界标准做法：
// `全量内容库（可能几万篇）
//     ↓ ① 召回（Recall）：多路并行"捞"出一小批候选，宁滥勿缺 —— 目标：快
//     ↓ ② 打分排序（Ranking）：对候选逐篇精算分数，排成最终 feed 流 —— 目标：准`
// 为什么不直接对全库打分？ 因为精算分数很贵（要查统计表、窗口表、算新鲜度）。所以先用廉价的查询（走索引取 Top-N）从几万篇里筛出几百篇候选池，再对池子里这几十上百篇做精细打分。 召回决定“谁有资格参赛”，排序决定“谁排前面”。

/// ## 这个项目里的四路召回
// 对应代码在 FeedStreamServiceImpl.java:L232-L264 （`buildArticleSnapshot` 方法）。每路召回就是一个简单的 Mapper 查询，取`perPath` 条：
// 路 名称 召回逻辑 作用 路1 最热 复用热度榜 ZSet，直接取`topArticleIds(1, perPath)` 保证头部热门内容一定出现 路2 最新 按`create_time` 倒序取 N 条（走`idx_create_time` 索引） 保证新内容有曝光机会，不被热度压制 路3 常看分区 `zone IN (用户偏好的分区)` ，取 perPath 条 个性化：保证用户爱看的分区内容入池 路4 常看分类 `category IN (用户偏好的分类)` ，取 perPath 条 个性化：保证用户爱看的分类内容入池
// 四路召回各自取一小批，合并去重后的候选池天然就包含了“热门保障 + 新鲜保障 + 个性化保障”三类内容，后续打分只需按权重决定它们的相对位置。

///### 打分
//    全库文章（几万篇）
//            ↓ 四路召回（每路各取 perPath 条，走索引的轻量查询）
//    候选池（~几百篇）
//            ↓ 过滤（作用域/负反馈/当日曝光）
//    最终候选（再少一点）
//            ↓ ★ 打分只发生在这里，逐篇循环算分 ★  打分使用自己配置的打分函数，以及自行配置的打分条件的权重


/// reading_history（唯一真源）
//     → FeedProfileSupport.aggregate()（窗口内计数取 Top-N + 完结占比判定）
//     → Redis 缓存（按 userId/anonId 隔离，TTL 滞后可接受）
//     → FeedStreamServiceImpl：召回扩池（分区/分类两路） + 打分加权（zoneMatch/categoryMatch/finishMatch） + 已读降权
//     → FeedScoreSupport.computeArticleScore / computeBookScore（唯一公式收口，热门榜偏好项传 0）


    /**
     * 加载用户画像（文章流与书籍流共用；批次十 10.5：带 Redis 缓存）
     *
     * <p>冷启动判定口径：readWindowDays 天内没有任何文章阅读记录 ⇒ 视为冷启动，
     * 分区/分类两路个性化召回全部跳过，退化为「全局热门 + 最新」，同时调用方把探索位比例
     * 上调到 coldStartExplorationRatio。窗口内只要有 ≥1 条阅读记录即视为非冷启动。</p>
     *
     * <p>匿名口径：匿名用户没有 reading_history（阅读上报只写登录用户），因此恒为冷启动；
     * 匿名缓存按 anonId 独立读写，不与 userId 混用。localStorage 丢失/换设备会得到新 anonId，
     * 画像归零——这是匿名标识本身的局限（不跨设备，P0 接受）。</p>
     *
     * <p>缓存失效策略：默认接受 TTL（profileCacheTtlSeconds）内的滞后，阅读行为不主动失效。
     * 成本判断：主动失效需要在阅读上报成功后多一次 Redis 写（与阅读链路跨模块耦合），
     * 收益只是把偏好滞后从「≤TTL」降到秒级；画像只影响排序、不影响内容可见性，滞后可接受。
     * 触发条件：后续出现「实时兴趣」需求或推荐明显滞后投诉时，再在阅读上报成功后删除
     * {@code content:feed:profile:u{userId}} 一个键（成本：每篇阅读多一次 Redis DEL）。</p>
     *
     * @param query 查询入参（取 userId / anonId 决定画像身份与缓存键）
     * @return 画像对象（无行为数据时 coldStart=true，个性化召回路退化全局）
     */
    public FeedProfile load(FeedQuery query) {
        // 画像缓存键：登录/匿名分离；无任何身份时返回 null（跳过缓存，直接回源）
        String cacheKey = buildProfileCacheKey(query);

        // 步骤1：缓存命中直接返回（避免每次翻页/续池都聚合一次 reading_history）
        if (cacheKey != null) {
            FeedProfile cached = readCachedProfile(cacheKey);
            if (cached != null) {
                // 命中指标：直接复用缓存，未触发回源聚合
                contentMetrics.recordProfileCacheHit();
                return cached;
            }
            // 未命中指标：接下来会回源聚合并覆盖写缓存（缓存键为空的场景不算命中/未命中）
            contentMetrics.recordProfileCacheMiss();
        }

        // 步骤2：匿名无行为数据源 → 直接冷启动画像，不发空查询
        FeedProfile profile = query.getUserId() == null ? new FeedProfile() : aggregate(query.getUserId());

        // 步骤3：回源结果写回缓存（匿名冷启动画像同样按 anonId 隔离缓存，便于后续接入匿名行为时格式一致）
        if (cacheKey != null) {
            writeCachedProfile(cacheKey, profile);
        } 
        return profile;
    }

    /**
     * 回源聚合登录用户画像（分区偏好 + 分类偏好 + 已读集合 + 书籍状态偏好）
     *
     * @param userId 登录用户 ID
     * @return 画像对象（无近期文章阅读时 coldStart=true）
     */
    private FeedProfile aggregate(Long userId) {
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        // 已读判定窗口起点：超过窗口的旧阅读记录不参与降权，避免长期压制旧文章
        LocalDateTime readWindowStart = LocalDateTime.now().minusDays(stream.getReadWindowDays());

        // 步骤1：取近窗口内的文章阅读历史（按最后阅读时间倒序，分页插件限量，禁止拼 LIMIT 片段）
        Page<ReadingHistory> historyPage = Page.of(1, stream.getProfileSampleSize(), false);
        LambdaQueryWrapper<ReadingHistory> historyWrapper = new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getUserId, userId)
                .eq(ReadingHistory::getBizType, ReadingTargetTypeEnum.ARTICLE)
                .ge(ReadingHistory::getUpdateTime, readWindowStart)
                .orderByDesc(ReadingHistory::getUpdateTime);
        List<ReadingHistory> articleHistories = readingHistoryMapper.selectList(historyPage, historyWrapper);

        // 已读文章 ID 集合（用于打分降权；为空说明没有近期文章阅读行为）
        Set<Long> readArticleIds = articleHistories.stream()
                .map(ReadingHistory::getBizId)
                .collect(Collectors.toCollection(HashSet::new));

        // 画像骨架：默认冷启动，下面聚合出偏好后再关闭冷启动
        FeedProfile profile = new FeedProfile().setReadArticleIds(readArticleIds);

        // 步骤2：近期没有文章阅读 → 仍可能有书籍阅读，继续聚合书籍偏好；文章两路个性化保持冷启动退化
        if (!readArticleIds.isEmpty()) {
            // 按已读文章 ID 反查分区/分类（只取三列，避开正文大字段）
            List<Article> readArticles = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                    .in(Article::getId, readArticleIds)
                    .select(Article::getId, Article::getZone, Article::getCategory));

            // 分区阅读次数统计
            Map<ZoneEnum, Long> zoneCounts = new HashMap<>();
            // 分类阅读次数统计
            Map<ArticleCategoryEnum, Long> categoryCounts = new HashMap<>();
            for (Article article : readArticles) {
                if (article.getZone() != null) {
                    zoneCounts.merge(article.getZone(), 1L, Long::sum);
                }
                if (article.getCategory() != null) {
                    categoryCounts.merge(article.getCategory(), 1L, Long::sum);
                }
            }

            // 取阅读次数最高的分区作为唯一偏好（分区只有 1/2 两档，取多个会让匹配项失效）
            Set<ZoneEnum> preferredZones = zoneCounts.entrySet().stream()
                    .sorted(Map.Entry.<ZoneEnum, Long>comparingByValue().reversed())
                    .limit(TOP_ZONE_COUNT)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(HashSet::new));
            // 取阅读次数最高的两个分类（保留一定多样性，避免只剩单一分类）
            Set<ArticleCategoryEnum> preferredCategories = categoryCounts.entrySet().stream()
                    .sorted(Map.Entry.<ArticleCategoryEnum, Long>comparingByValue().reversed())
                    .limit(TOP_CATEGORY_COUNT)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(HashSet::new));

            profile.setPreferredZones(preferredZones)
                    .setPreferredCategories(preferredCategories)
                    .setColdStart(false);
        }

        // 步骤3：近 bookWindow 内已读书籍 → 判断完结/连载偏好（书籍流 0.1 权重项）
        profile.setPreferredBookStatus(resolvePreferredBookStatus(userId));
        return profile;
    }

    /**
     * 生成画像缓存键（按身份隔离）
     *
     * @param query 查询入参
     * @return 缓存键；登录 u{userId} / 匿名 a{anonId}；两者都无时返回 null（不读不写缓存）
     */
    private String buildProfileCacheKey(FeedQuery query) {
        // 登录身份优先：登录用户禁止落到匿名键，防串号
        if (query.getUserId() != null) {
            return FeedConstant.PROFILE_KEY_PREFIX + FeedConstant.IDENTITY_USER_PREFIX + query.getUserId();
        }
        // 匿名身份：过滤器保证有值；异常缺失时跳过缓存，不影响聚合
        if (StringUtils.hasText(query.getAnonId())) {
            return FeedConstant.PROFILE_KEY_PREFIX + FeedConstant.IDENTITY_ANON_PREFIX + query.getAnonId();
        }
        return null;
    }

    /**
     * 读取画像缓存
     *
     * @param cacheKey 缓存键
     * @return 还原后的画像；未命中/类型不符/脏数据返回 null（调用方回源聚合）
     */
    private FeedProfile readCachedProfile(String cacheKey) {
        Object value = redisUtil.get(cacheKey);
        // 只接受本类写入的载体类型：旧格式/脏值一律按未命中处理，回源覆盖写
        if (value instanceof FeedProfileCache cache) {
            return fromCache(cache);
        }
        return null;
    }

    /**
     * 写入画像缓存
     *
     * @param cacheKey 缓存键
     * @param profile  画像对象
     */
    private void writeCachedProfile(String cacheKey, FeedProfile profile) {
        // TTL 配置化：非正数表示关闭缓存，直接跳过写入
        long ttlSeconds = contentProperties.getFeed().getStream().getProfileCacheTtlSeconds();
        if (ttlSeconds <= 0) {
            return;
        }
        // 转缓存载体后写入：Redis 异常由 RedisUtil 吞掉，画像链路不因缓存失败中断
        redisUtil.set(cacheKey, toCache(profile), ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 画像对象转缓存载体（枚举转码/名称）
     *
     * @param profile 画像对象
     * @return 缓存载体
     */
    private FeedProfileCache toCache(FeedProfile profile) {
        // 分区枚举转入库码（1/2）
        Set<Integer> zoneCodes = new HashSet<>();
        for (ZoneEnum zone : profile.getPreferredZones()) {
            zoneCodes.add(zone.getCode());
        }
        // 分类枚举转中文分类名（与 DB 直存口径一致）
        Set<String> categoryNames = new HashSet<>();
        for (ArticleCategoryEnum category : profile.getPreferredCategories()) {
            categoryNames.add(category.getName());
        }
        return new FeedProfileCache()
                .setColdStart(profile.isColdStart())
                .setReadArticleIds(profile.getReadArticleIds())
                .setPreferredZoneCodes(zoneCodes)
                .setPreferredCategoryNames(categoryNames)
                .setPreferredBookStatusCode(profile.getPreferredBookStatus().getCode())
                .setPreferredTags(profile.getPreferredTags());
    }

    /**
     * 缓存载体还原画像
     *
     * @param cached 缓存载体
     * @return 画像对象（未知码/未知分类名直接丢弃，避免历史脏数据阻断画像加载）
     */
    private FeedProfile fromCache(FeedProfileCache cached) {
        // 分区码转枚举（未知码跳过：枚举可能随版本收敛，旧缓存不应报错）
        Set<ZoneEnum> zones = new HashSet<>();
        for (Integer code : cached.getPreferredZoneCodes()) {
            ZoneEnum zone = resolveZone(code);
            if (zone != null) {
                zones.add(zone);
            }
        }
        // 分类名转枚举（复用枚举自带的静态解析，未命中返回 null 即丢弃）
        Set<ArticleCategoryEnum> categories = new HashSet<>();
        for (String name : cached.getPreferredCategoryNames()) {
            ArticleCategoryEnum category = ArticleCategoryEnum.resolve(name);
            if (category != null) {
                categories.add(category);
            }
        }
        // 集合字段做防御性拷贝：缓存对象可能被复用，避免还原画像与原缓存共享同一集合
        Set<Long> readIds = cached.getReadArticleIds() == null
                ? Collections.emptySet() : new HashSet<>(cached.getReadArticleIds());
        Set<String> tags = cached.getPreferredTags() == null
                ? Collections.emptySet() : new HashSet<>(cached.getPreferredTags());
        return new FeedProfile()
                .setColdStart(cached.isColdStart())
                .setReadArticleIds(readIds)
                .setPreferredZones(zones)
                .setPreferredCategories(categories)
                .setPreferredBookStatus(resolveBookStatus(cached.getPreferredBookStatusCode()))
                .setPreferredTags(tags);
    }

    /**
     * 分区码转枚举
     *
     * @param code 入库值（1-普通 / 2-作家）
     * @return 命中枚举；未知码返回 null
     */
    private ZoneEnum resolveZone(Integer code) {
        if (code == null) {
            return null;
        }
        // 枚举仅两个取值，遍历成本可忽略
        for (ZoneEnum zone : ZoneEnum.values()) {
            if (zone.getCode() == code) {
                return zone;
            }
        }
        return null;
    }

    /**
     * 书籍状态码转枚举
     *
     * @param code 入库值（0-连载中 / 1-已完结 / 2-下架）
     * @return 命中枚举；未知码回退默认偏好「已完结」，保证排序权重项不丢
     */
    private BookStatusEnum resolveBookStatus(int code) {
        for (BookStatusEnum status : BookStatusEnum.values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return BookStatusEnum.COMPLETED;
    }

    /**
     * 判断用户偏好「已完结」还是「连载中」书籍
     *
     * <p>口径：取最近 BOOK_SAMPLE_SIZE 条书籍阅读历史，反查书籍状态；
     * 已完结占比 > 50% 视为偏好完结，否则偏好连载。无样本时默认偏好已完结
     * （大多数读者更愿意点开已完结作品，可在 yml 权重侧继续调）。</p>
     *
     * @param userId 登录用户 ID
     * @return 偏好书籍状态（COMPLETED / SERIALING）
     */
    private BookStatusEnum resolvePreferredBookStatus(Long userId) {
        // 最近书籍阅读历史（只取 ID，够判断状态来源）
        Page<ReadingHistory> bookPage = Page.of(1, BOOK_SAMPLE_SIZE, false);
        List<ReadingHistory> bookHistories = readingHistoryMapper.selectList(bookPage,
                new LambdaQueryWrapper<ReadingHistory>()
                        .eq(ReadingHistory::getUserId, userId)
                        .eq(ReadingHistory::getBizType, ReadingTargetTypeEnum.BOOK)
                        .orderByDesc(ReadingHistory::getUpdateTime));
        if (bookHistories.isEmpty()) {
            return BookStatusEnum.COMPLETED;
        }
        
        // 已读书籍 ID 集合
        Set<Long> bookIds = bookHistories.stream()
                .map(ReadingHistory::getBizId)
                .collect(Collectors.toCollection(HashSet::new));
        // 只查状态两列，避免把简介等无用字段带出来
        List<Book> books = bookMapper.selectList(new LambdaQueryWrapper<Book>()
                .in(Book::getId, bookIds)
                .select(Book::getId, Book::getStatus));
        if (books.isEmpty()) {
            return BookStatusEnum.COMPLETED;
        }
        // 已读完本书数（状态=已完结）
        long completedCount = books.stream()
                .filter(book -> book.getStatus() == BookStatusEnum.COMPLETED)
                .count();
        return completedCount * 2 > books.size() ? BookStatusEnum.COMPLETED : BookStatusEnum.SERIALING;
    }
}
