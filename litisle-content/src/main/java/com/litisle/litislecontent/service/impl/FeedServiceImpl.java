package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.utils.JsonUtil;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.vo.FeedItemVO;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.service.FeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 推荐流域实现（缓存三防 + 展示前回查，[7.1]/[7.2]）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private final ArticleMapper articleMapper;

    private final DistributedLock distributedLock;

    private final RedisUtil redisUtil;

    private final ContentProperties contentProperties;

    /** 空结果短缓存 TTL（秒）：防穿透专用，远短于正常 TTL（[7.1] 缓存三防之二） */
    private static final long EMPTY_CACHE_TTL_SECONDS = 60;

    /** 互斥重建锁等待（秒）：未抢到锁的请求等待重建方回填后重读缓存 */
    private static final long REBUILD_LOCK_WAIT_SECONDS = 2;

    /** 未抢到锁重读缓存前的让渡间隔（毫秒） */
    private static final long LOCK_WAIT_PAUSE_MS = 200;

    /**
     * 推荐流分页（预计算推荐链路，zone 1/2 聚合视图）：
     * 互斥重建（单飞锁）防击穿 + 空结果短缓存防穿透 + TTL ±10% 抖动防雪崩；
     * 展示前回查 status=2 且 visibility=1，已下架/转私密即时剔除（防残留曝光）
     *
     * @param userId   当前登录用户ID（个性化排序预留，一期仅缓存维度）
     * @param pageNo   页码（1 起）
     * @param pageSize 页大小（默认 feed.page-size=20）
     * @return 推荐流条目列表
     */
    @Override
    public List<FeedItemVO> feed(Long userId, int pageNo, int pageSize) {

        /**
         * 推荐流查询流程（缓存三防）：
         *
         * ① 页参兜底：页码至少 1，每页大小封顶（防恶意大分页拖垮缓存与 DB）。
         * ② 快路径：命中缓存直接进展示前回查（热路径不增加额外开销）。
         * ③ 缓存未命中 → 互斥重建（防击穿）：
         *    - 抢到锁：再查一次缓存（可能已被别人回填）；仍无则查库回填；
         *      空结果存短缓存（防穿透），正常结果 TTL 加抖动（防雪崩）；
         *    - 未抢到锁：稍等片刻重读缓存；仍无则兑底直查库（不拒绝服务）。
         * ④ 展示前回查：无论缓存还是直查，都剔除已下架/转私密/已删除的文章
         *    （缓存过期前的残留曝光封口，隐私红线）。
         */

        // 页参兜底：pageNo ≥1；pageSize 上限收口配置（防恶意大分页拖垮缓存与 DB）
        int safePageNo = Math.max(pageNo, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1),
                contentProperties.getFeed().getPageSize());
        String cacheKey = CacheConstant.CONTENT_FEED + safePageNo + ":" + safePageSize;
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
                List<FeedItemVO> items = loadFromDb(safePageNo, safePageSize);
                // 空结果短缓存防穿透：恶意翻页/冷页不再持续打库；正常结果 TTL ±10% 抖动防雪崩
                long ttl = items.isEmpty()
                        ? EMPTY_CACHE_TTL_SECONDS : jitteredTtl();
                redisUtil.set(cacheKey, JsonUtil.toJson(items), ttl, TimeUnit.SECONDS);
                return items;
            } finally {
                distributedLock.unlock(lockKey);
            }
        }
        // 步骤4：未抢到锁：稍等片刻重读缓存（重建方大概率已回填），仍没有就兜底直查库（不拒绝服务）
        try {
            Thread.sleep(LOCK_WAIT_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cached = redisUtil.get(cacheKey);
        if (cached != null) {
            return recheckFilter(parse(cached));
        }
        return recheckFilter(loadFromDb(safePageNo, safePageSize));
    }

    /**
     * 缓存回源查询：zone 1/2 聚合候选（status=2 已发布 + visibility=1 公开 + 未软删）发布时间倒序
     * （阶段12：zone=3 分区已取消，推荐流改为 zone 1/2 聚合视图；排序方式另立项评估）
     *
     * @param pageNo   页码（1 起）
     * @param pageSize 页大小
     * @return 推荐流条目列表
     */
    private List<FeedItemVO> loadFromDb(int pageNo, int pageSize) {
        // 用 Page 替代手拼 LIMIT/OFFSET：分页插件标准方式（searchCount=false 不触发 COUNT），
        // 偏移量由插件按 pageNo/pageSize 统一计算，消除字符串拼接与手算偏移的维护风险
        List<Article> articles = articleMapper.selectList(Page.of(pageNo, pageSize, false),
                new LambdaQueryWrapper<Article>()
                        .in(Article::getZone, ZoneEnum.NORMAL, ZoneEnum.WRITER)
                        .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                        .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                        .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                        .orderByDesc(Article::getCreateTime));
        return articles.stream().map(article -> new FeedItemVO()
                .setArticleId(article.getId())
                .setTitle(article.getTitle())
                .setSummary(article.getSummary())
                .setCoverFileId(article.getCoverFileId())
                .setAuthorId(article.getAuthorId())
                .setViewCnt(article.getViewCnt())
                .setLikeCnt(article.getLikeCnt())
                .setPublishTime(article.getCreateTime()))
                .toList();
    }

    /**
     * 展示前回查（[7.2]）：缓存 TTL 内文章可能已下架/转私密/软删——渲染前批量回查权威态，
     * 不满足 status=2 且 visibility=1 的条目即时剔除（防残留曝光，隐私红线）
     *
     * @param items 缓存/回源所得条目
     * @return 剔除后的可见条目
     */
    private List<FeedItemVO> recheckFilter(List<FeedItemVO> items) {
        if (items.isEmpty()) {
            return items;
        }
        List<Long> ids = items.stream().map(FeedItemVO::getArticleId).toList();
        Set<Long> visibleIds = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                        .in(Article::getId, ids)
                        .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                        .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                        .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                        .select(Article::getId)).stream()
                .map(Article::getId)
                .collect(Collectors.toSet());
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