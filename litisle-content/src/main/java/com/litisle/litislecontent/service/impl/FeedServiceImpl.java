package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.utils.JsonUtil;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.vo.FeedItemVO;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.service.FeedService;
import com.litisle.litislecontent.service.support.FeedHotRankSupport;
import com.litisle.litislecontent.service.support.FeedLocalFallbackSupport;
import com.litisle.litislecontent.support.FeedItemAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 推荐流域实现（缓存三防 + 展示前回查 + 热度榜切流，[7.1]/[7.2]）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private final ArticleMapper articleMapper;

    private final DistributedLock distributedLock;

    private final RedisUtil redisUtil;

    private final ContentProperties contentProperties;

    /** 热度榜支撑（阶段14：榜序取数/灌榜，榜空降级时间倒序兜底） */
    private final FeedHotRankSupport feedHotRankSupport;

    /** 本地兜底支撑（P4 Redis 全宕：JVM 信号量单飞 + last-good 快照，按 cacheKey 粒度） */
    private final FeedLocalFallbackSupport feedLocalFallbackSupport;

    /** 空结果短缓存 TTL（秒）：防穿透专用，远短于正常 TTL（[7.1] 缓存三防之二） */
    private static final long EMPTY_CACHE_TTL_SECONDS = 60;

    /** 互斥重建锁等待（秒）：未抢到锁的请求等待重建方回填后重读缓存 */
    private static final long REBUILD_LOCK_WAIT_SECONDS = 2;

    /** 未抢到锁重读缓存前的让渡间隔（毫秒） */
    private static final long LOCK_WAIT_PAUSE_MS = 200;

    /** 本地兜底等待单飞闸门超时（秒）：等待超时只取 last-good 快照，绝不放大并发打 DB（P4） */
    private static final long LOCAL_FALLBACK_WAIT_SECONDS = 2;


    /**
     * 推荐流分页（预计算推荐链路，zone 1/2 聚合视图）：
     * 互斥重建（单飞锁）防击穿 + 空结果短缓存防穿透 + TTL ±10% 抖动防雪崩；
     * 展示前回查 status=2 且 visibility=1，已下架/转私密即时剔除（防残留曝光）；
     * Redis 全宕兜底（批次E P4 定案 D-4 2026-11-03）：缓存读/单飞锁任一环节异常
     * 视为 Redis 不可用，切本地单飞 + last-good 快照（兜住 N 并发直查 DB）
     *
     * @param userId   当前登录用户ID（个性化排序预留，一期仅缓存维度）
     * @param pageNo   页码（1 起）
     * @param pageSize 页大小（默认 feed.page-size=20）
     * @return 推荐流条目列表
     */
    @Override
    public List<FeedItemVO> feed(Long userId, int pageNo, int pageSize) {
        // 页参兜底：pageNo ≥1；pageSize 上限收口配置（防恶意大分页拖垮缓存与 DB）
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1),
                contentProperties.getFeed().getPageSize());
        String cacheKey = CacheConstant.CONTENT_FEED + safePageNo + ":" + safePageSize;
        try {
            return feedViaRedis(cacheKey, safePageNo, safePageSize);
        } catch (Exception e) {
            // Redis 全宕兜底（P4 场景C）：缓存读/单飞锁失效后若无闸门，全部请求直查 DB（N 并发=N 次）；
            // 本地信号量单飞把 DB 并发压到每 key 1 路，其余请求等 last-good 快照（即限流/熔断收口）
            log.warn("feed Redis 链路异常，降级本地兜底：cacheKey={}", cacheKey, e);
            return feedViaLocalFallback(cacheKey, safePageNo, safePageSize);
        }
    }

    /**
     * Redis 主链路（缓存三防）。
     *
     * <p>推荐流查询流程：</p>
     *
     * ① 快路径：命中缓存直接进展示前回查（热路径不增加额外开销）。
     * ② 缓存未命中 → 互斥重建（防击穿）：
     *    - 抢到锁：再查一次缓存（可能已被别人回填）；仍无则查库回填；
     *      空结果存短缓存（防穿透），正常结果 TTL 加抖动（防雪崩）；
     *    - 未抢到锁：稍等片刻重读缓存；仍无则兜底直查库（不拒绝服务）。
     * ③ 展示前回查 MySql：无论缓存还是直查，都剔除已下架/转私密/已删除的文章
     *    （缓存过期前的残留曝光封口，隐私红线）。⚠️ 如果将来 QPS 真的上去了，加一层本地缓存"已下架ID集合"即可
     *
     * @param cacheKey     结果缓存键（已按兜底页参组装）
     * @param safePageNo   兜底后页码
     * @param safePageSize 兜底后页大小
     * @return 推荐流条目列表
     */
    private List<FeedItemVO> feedViaRedis(String cacheKey, int safePageNo, int safePageSize) {
        // 快路径：命中缓存直接进展示前回查（击穿/穿透防护不影响热路径 RT）
        String cached = redisUtil.get(cacheKey);
        if (cached != null) {
            return recheckFilter(parse(cached));
        }
        // 互斥重建防击穿：单飞锁，仅一个请求回源重建，其余等待回填后重读
        String lockKey = CacheConstant.LOCK_PREFIX + cacheKey;
        if (distributedLock.tryLock(lockKey, REBUILD_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
            try {
                // 双检：抢到锁时缓存可能已被上一持有者回填
                cached = redisUtil.get(cacheKey);
                if (cached != null) {
                    return recheckFilter(parse(cached));
                }
                List<FeedItemVO> items = loadFromDb(cacheKey, safePageNo, safePageSize);
                // 空结果短缓存防穿透：恶意翻页/冷页不再持续打库；正常结果 TTL ±10% 抖动防雪崩
                long ttl = items.isEmpty()
                        ? EMPTY_CACHE_TTL_SECONDS : jitteredTtl();
                redisUtil.set(cacheKey, JsonUtil.toJson(items), ttl, TimeUnit.SECONDS);
                return items;
            } finally {
                distributedLock.unlock(lockKey);
            }
        }
        // 未抢到锁：稍等片刻重读缓存（重建方大概率已回填），仍没有就兜底直查库（不拒绝服务）
        try {
            Thread.sleep(LOCK_WAIT_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cached = redisUtil.get(cacheKey);
        if (cached != null) {
            return recheckFilter(parse(cached));
        }
        return recheckFilter(loadFromDb(cacheKey, safePageNo, safePageSize));
    }

    /**
     * Redis 全宕本地兜底（批次E P4 定案 D-4 2026-11-03）。
     *
     * 作用：
     * JVM 信号量本地单飞（每 cacheKey 单许可）——仅一个线程回源 DB，其余线程等待：
     * 等到闸门 → 双检快照命中直接返回（回源方已回填）；等待超时 → 只取 last-good 快照，
     * 快照缺失返回空列表（绝不放大并发打 DB）。feed 的 key 组合有限（page×size），
     * 快照内存占用可控（FeedLocalFallbackSupport 内有条目上限保护）。
     *
     * Redis 挂了之后，这个方法负责在 JVM 本地用"单飞限流"保护数据库，保证同一时刻同一个分页请求只有 1 个线程去查 DB，其余线程要么等、要么直接读旧数据、要么返回空。
     *
     * @param cacheKey     结果缓存键
     * @param safePageNo   兜底后页码
     * @param safePageSize 兜底后页大小
     * @return 推荐流条目列表
     */
    private List<FeedItemVO> feedViaLocalFallback(String cacheKey, int safePageNo, int safePageSize) {

        //第 1 步：拿闸门
        // 本地单飞闸门：尝试拿每 key 单许可（最多等 2s，超时不打 DB）
        Semaphore gate = feedLocalFallbackSupport.gate(cacheKey);
        boolean acquired = false;

        //第 2 步：尝试拿许可（最多等 2 秒）
        try {
            acquired = gate.tryAcquire(LOCAL_FALLBACK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        //第 3 步：没拿到许可（超时）→ 降级返回
        if (!acquired) {
            // 等待超时：只取 last-good 快照（可能稍旧但可用），无快照返回空列表（不放大 DB 压力）
            List<FeedItemVO> snapshot = feedLocalFallbackSupport.snapshot(cacheKey);
            return snapshot != null ? recheckFilter(snapshot) : List.of();
        }

        //第 4 步：拿到许可 → 双检快照
        //我拿到了许可，但在我去查 DB 之前，再检查一下——有没有别人在我等待的这 2 秒内已经查过并存好了快照？
        try {
            // 双检：进闸前可能有前一轮已回填快照（对齐缓存双检范式）
            List<FeedItemVO> snapshot = feedLocalFallbackSupport.snapshot(cacheKey);
            if (snapshot != null) {
                return recheckFilter(snapshot);
            }

        //第 5 步：快照也没有 → 单飞回源 DB
            // 快照缺失：单飞回源 DB（loadFromDb 成功后自动更新 last-good 快照）
            return recheckFilter(loadFromDb(cacheKey, safePageNo, safePageSize));

        //第 6 步：释放闸门
        } finally {
            gate.release();
        }

//  完整降级链路总结：
//        正常情况：
//        Redis 活着 → 读缓存 → 直接返回（0 次 DB）
//
//        Redis 挂了：
//        正常 key → 独立闸门 → 单飞查 DB → 存快照 → 后续读快照
//        溢出 key → 溢出闸门 → 串行查 DB → 存快照 → 后续读快照
//
//        溢出闸门被占（并发高）：
//        等 2 秒 → 拿到许可 → 正常走
//        等 2 秒 → 没拿到 → 读快照（有就返回，没有返回空列表）
//
//        DB 也挂了：
//        溢出闸门永远不释放 → 所有溢出 key 返回空列表
//        正常 key 的独立闸门不受影响（但如果 DB 卡死，独立闸门也会卡）

    }

    
    /**
     * 缓存回源查询（阶段14 已切流）：hot.enabled 且榜非空时优先读热度榜
     * （FeedHotRankSupport#topArticleIds），榜空（灌榜任务未跑/已过期）或开关关闭时
     * 降级时间倒序直查兜底（兜底契约，防榜异常空流）；
     * 第 1 页榜空时异步触发重建（批次E P7 + E.4 补漏①：不等下一轮 5min 定时压缩空窗，
     * 深页超界不触发）；回源成功即更新 last-good 快照（P4：Redis 全宕期间的兜底数据源，
     * 正常链路始终近似最新）
     *
     * @param cacheKey 结果缓存键（快照按同粒度保存，兜底链路直接命中）
     * @param pageNo   页码（1 起）
     * @param pageSize 页大小
     * @return 推荐流条目列表
     */
    private List<FeedItemVO> loadFromDb(String cacheKey, int pageNo, int pageSize) {
        // ========== 业务流程：决定"这一页数据从哪来、按什么顺序排" ==========
        // 主规则：热度榜优先（按热度分排序展示）；榜不可用时降级时间倒序（保证推荐流永远有数据，不开天窗）

        List<FeedItemVO> items;

        // 第 1 步：判断热度榜总开关（feed.hot.enabled，yml 配置）
        // 开关关闭 = 明确不用榜，直接走时间倒序，连 Redis 都不碰
        if (contentProperties.getFeed().getHot().isEnabled()) {

            // 第 2 步：取榜——调 FeedHotRankSupport#topArticleIds
            // 【该方法做什么】从 Redis 热度榜（ZSet 结构，member=文章ID，score=热度分）中，
            // 按热度分"从高到低"取出本页对应的文章 ID 列表（ZREVRANGE 分页：offset=(pageNo-1)*pageSize，count=pageSize）；
            // 榜缺失（TTL 30min 过期/Redis 重启丢数据）或页码超过榜容量（topN=200，如第 11 页起）时返回空列表——
            // 返回空 ≠ 没有文章，只是"榜上没有这一页"，由下面第 3 步决定怎么办
            List<Long> hotIds = feedHotRankSupport.topArticleIds(pageNo, pageSize);

            if (!hotIds.isEmpty()) {
                // 第 3a 步：榜上有这一页 → 按榜序组装
                // 调本类 loadByHotRank：拿榜上的文章 ID 批量查 DB（补可见性过滤 + 排除正文大字段），
                // 再按榜单名次重排顺序（IN 查询不保序），已下架/私密/软删的文章被跳过
                items = loadByHotRank(hotIds);
            } else {
                // 第 3b 步：榜上没有这一页 → 降级时间倒序 +（仅第 1 页）触发榜重建
                //
                // 榜空时异步触发重建——调 FeedHotRankSupport#triggerRebuildAsync
                // 【该方法做什么】往 JVM 内单线程池丢一个"重建热度榜"的任务（异步，不阻塞当前请求）：
                //   ① 先判断灌榜开关 refill-enabled（关闭的实例没资格灌榜，直接放弃）；
                //   ② CAS 去抖：同一时刻只允许排队一次（榜缺失期间大量并发请求不会每个都触发重建）；
                //   ③ 内部调 refillAuto：按榜/水位状态自动选"全量重建"（查全库→逐篇算热度分→
                //      写影子键→rename 原子换榜）或"增量灌榜"（只捞近期计数有变化的文章重算分数）；
                //   ④ 抢不到灌榜分布式锁（定时任务/其他实例正在灌）直接放弃，失败等下一轮 5min 定时任务兜底。
                // 仅第 1 页榜空才触发：第 1 页为空 ⇒ 榜整体缺失/TopN 未满，重建有意义；
                // 深页为空只是「榜非空、页超界」，重建也变不出第 11 页，不触发防深翻页反复 refillAuto
                if (pageNo == 1) {
                    feedHotRankSupport.triggerRebuildAsync();
                }
                // 无论是否触发重建，本请求都不能干等（重建是异步的）——
                // 立即用时间倒序直查 DB 兜底返回，保证本次请求有数据可看
                items = loadByTimeDesc(pageNo, pageSize);
            }
        } else {
            // 开关关闭分支：直接时间倒序直查（zone 1/2 + 已发布 + 公开 + 未软删，按创建时间倒序分页）
            items = loadByTimeDesc(pageNo, pageSize);
        }

        // 第 4 步：回源成功落 last-good 快照——调 FeedLocalFallbackSupport#save
        // 【该方法做什么】把这次从 DB 查到的结果存进 JVM 内存 Map（key=cacheKey，值带存入时间戳），
        // 作为"Redis 全宕"时的兜底数据源：Redis 正常时每次回源都刷新（快照始终近似最新），
        // Redis 挂掉后其他请求可以读这份旧数据顶住，而不用全部直查 DB
        feedLocalFallbackSupport.save(cacheKey, items);
        return items;
    }

    /**
     * 时间倒序直查兜底：zone 1/2 聚合候选（status=2 已发布 + visibility=1 公开 + 未软删）发布时间倒序
     *
     * 查 Article 表，但不要查正文内容那个字段,按文章创建时间排序
     *
     * @param pageNo   页码（1 起）
     * @param pageSize 页大小
     * @return 推荐流条目列表
     */
    private List<FeedItemVO> loadByTimeDesc(int pageNo, int pageSize) {
        // 用 Page 替代手拼 LIMIT/OFFSET：分页插件标准方式（searchCount=false 不触发 COUNT），
        // 偏移量由插件按 pageNo/pageSize 统一计算，消除字符串拼接与手算偏移的维护风险；
        // P6 批次E 2026-11-03：回源只装配 FeedItemVO 卡片字段，排除 content_html（longtext 大字段）
        List<Article> articles = articleMapper.selectList(Page.of(pageNo, pageSize, false),
                new LambdaQueryWrapper<Article>()
                        // 可推荐分区统一取常量集合（批次十 10.1：与回查门控共用同一口径，防两处各写一份）
                        .in(Article::getZone, FeedConstant.ARTICLE_RECOMMENDABLE_ZONES)
                        .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                        .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                        .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                        .select(Article.class, i -> !Article.COLUMN_CONTENT_HTML.equals(i.getColumn()))//查询时排除 content_html 这个大字段，只查卡片展示需要的列。
                        .orderByDesc(Article::getCreateTime));
        return articles.stream().map(this::toFeedItem).toList();
    }

    /**
     * 按热度榜组装推荐流条目。
     *
     * 作用：
     * 依据榜上文章 ID 批量回查明细，按 ZSet 返回的名次顺序组装（in 查询不保序，必须重排）；
     * 回查补 status=2 + visibility=1 + 未软删过滤（批次E P2 定案 D-1 2026-11-03：
     * ZREM 剔除失败的残留成员兜底双保险，对齐 search C3 可见性红线口径）；
     * 查询排除 content_html 大字段（P6，回源只装配卡片字段）。
     *
     * loadByHotRank 是"按热度榜查文章明细并组装成推荐流"的方法：拿 Redis 热度榜上的文章 ID 列表，
     * 批量去数据库查出每篇文章的卡片信息，按榜单名次重排顺序，同时过滤掉已下架/私密/已删的文章。
     *
     * @param hotIds 榜上文章 ID（按热度分倒序）
     * @return 按榜序排列的推荐流条目
     */
    private List<FeedItemVO> loadByHotRank(List<Long> hotIds) {

        //第 1 步：批量回查明细
            //拿热度榜上的所有文章 ID，一次 IN 查询批量从数据库取文章数据。
        // 批量回查明细（勿逐条查，防 N+1），补可见性过滤（C3 兜底双保险）+ 排除大字段，转 Map 供榜序重排
        Map<Long, FeedItemVO> voMap = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .in(Article::getId, hotIds)
                        .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                        .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                        .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                        .select(Article.class, i -> !Article.COLUMN_CONTENT_HTML.equals(i.getColumn())))
                .stream()
                .map(this::toFeedItem)
                .collect(Collectors.toMap(FeedItemVO::getArticleId, Function.identity()));

        //第 2 步：按榜序重排
        // 按榜序重排（in 查询不保序，榜单名次即展示顺序）；缺失/已不可见文章跳过
            //重排不是靠排序算法，是靠"遍历顺序"。
            //最后返回的就是：按热度排名排列、且全部合法可见的文章列表。
        return hotIds.stream()
                .map(voMap::get)
                .filter(Objects::nonNull)
                .toList();

                
//完整流程
//        Redis 热度榜返回 [id=103, id=101, id=105]（按热度倒序）
//            ↓
//        IN 查询 DB → 拿到 101、103、105 的文章数据（顺序不确定）
//            ↓
//        转 Map：{101: vo1, 103: vo2, 105: vo3}
//            ↓
//        按 hotIds 顺序重排：vo2(103) → vo1(101) → vo3(105)
//            ↓
//        filter null（如果有已下架的被跳过）
//            ↓
//        返回最终列表

    }

    /**
     * Article 转 FeedItemVO（时间倒序/热度榜两条回源链路共用装配）
     * <p>批次十 10.1：装配逻辑下沉 {@link FeedItemAssembler}，与新无限刷流共用同一份字段口径</p>
     */
    private FeedItemVO toFeedItem(Article article) {
        return FeedItemAssembler.toArticleItem(article);
    }

    /**
     * 展示前回查（[7.2]）：缓存 TTL 内文章可能已下架/转私密/软删——渲染前批量回查权威态，
     * 不满足 status=2 且 visibility=1 的条目即时剔除（防残留曝光，隐私红线）
     *
     * recheckFilter 是展示前的最后一道安全门：拿着缓存或快照里的文章列表，去
     * 数据库批量回查每篇文章的"当前真实状态"，把已下架、转私密、被软删的文章当场剔除，保证用户看到的每一条都是合法可见的。
     *
     * @param items 缓存/回源所得条目
     * @return 剔除后的可见条目
     */
    private List<FeedItemVO> recheckFilter(List<FeedItemVO> items) {

        //第 1 步：空列表短路
        if (items.isEmpty()) {
            return items;
        }

        //第 2 步：从列表中提取文章 ID
        List<Long> ids = items.stream().map(FeedItemVO::getArticleId).toList();

        //第 3 步：批量回查 DB，只查"当前合法可见"的文章 ID
        // 批次十 10.1：分区也纳入回查门控（需求条款 6 要求 zone ∈ {1,2}），与召回侧共用同一常量集合
        Set<Long> visibleIds = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .in(Article::getId, ids)
                        .in(Article::getZone, FeedConstant.ARTICLE_RECOMMENDABLE_ZONES)
                        .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                        .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                        .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                        .select(Article::getId)).stream()
                .map(Article::getId)
                .collect(Collectors.toSet());

        //第 4 步：过滤掉不合法的条目
        return items.stream()
                .filter(item -> visibleIds.contains(item.getArticleId()))
                .toList();
    }

    /**
     * 缓存 JSON 反序列化（空串/脏数据容错返回空列表）
     */
    private static List<FeedItemVO> parse(String json) {
        FeedItemVO[] arr = JsonUtil.fromJson(json, FeedItemVO[].class);
        return arr == null ? List.of() : List.of(arr);
    }

    /**
     * 正常 TTL ±10% 抖动（防同批 key 同时过期雪崩）
     */
    private long jitteredTtl() {
        long base = contentProperties.getFeed().getCacheTtlSeconds();
        long jitter = (long) (base * 0.1);
        return base + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }
}
