package com.litisle.litislecontent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 推荐流热度榜骨架（阶段13：热度榜方案定案，social 模块落地前仅预留，零调用零影响）：
 *
 * 当前实际生效的排序是：纯浏览量（PV）驱动的热度分倒序排序，但代码架构已经按“完整热度榜”设计好了，只是因为社交模块还没上线，暂时退化成了这样。
 *
 * 【方案定位】/feed 排序由「时间倒序」升级为「热度分排序」，热度分公式（Hacker News 同款时间衰减）：
 *   score = (like_cnt × 点赞权重 + comment_cnt × 评论权重 + view_cnt × 浏览权重)
 *           / (发布小时数 + 小时偏移) ^ 衰减指数
 *   分母随发布时间增大——新文章分母小易冒头，旧文章自然沉底；权重外置 yml 可调。
 *
 * 【当前状态】阶段14 已切流——like_cnt/comment_cnt 恒 0（social 未开发），公式退化为纯浏览量排序；
 *   灌榜任务 FeedHotRankRefillJob 按 5min 周期编排（P1 增量优化 2026-10-28）：
 *   高频增量轮（默认 5min）按增量水位只捞 stat_update_time 变更文章重算 ZADD（DB 压力随变更量而非存量走），
 *   低频全量轮（默认 1h）全量重算兜底分数衰减收敛；/feed 优先读榜、榜空自动降级时间倒序直查。
 *   增量依据（批次E P2 定案 D-1 2026-11-03）：article.stat_update_time 仅由热度信号刷新
 *   （浏览量刷回 incrViewCnt / 点赞评论回写 updateCounters），热度分恰是这三类计数的函数——
 *   该列变更 ⇒ 分数可能变化，增量水位可完整覆盖全部热度信号变更源；
 *   update_time 则由任何业务 UPDATE 刷新（编辑/状态流转等，不影响热度分），
 *   浏览量刷回显式保留 update_time 原值，防老文章被顶到「最新列表」顶部。
 *
 * 【social 落地后回收点（阶段14 D10/D11，已登记 docs/next/Temporarily_not_implemented.md）】
 *   ① 数据源：social 计数回写（ContentInternal#updateCounters）成功后触发该文章热度分
 *      增量重算（ZADD 单成员），消除 5min 全量灌榜滞后（updateCounters 已留 TODO 接线点）；
 *   ② 权重调参：真实互动数据进来后按效果调 likeWeight/commentWeight/viewWeight（yml 外置可调）。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedHotRankSupport {

    /** 榜影子键后缀：灌榜先写影子键再 rename 原子换榜（灌榜期间读方无空榜窗口） */
    private static final String STAGING_SUFFIX = ":staging";

    /** 增量水位键后缀：记录上次增量灌榜时刻（epoch millis），增量轮只捞 update_time ≥ 水位的变更文章 */
    private static final String DELTA_WATERMARK_SUFFIX = ":delta-watermark";

    /** 全量水位键后缀：记录上次全量重灌时刻（epoch millis），到期触发全量校正分数衰减 */
    private static final String FULL_WATERMARK_SUFFIX = ":full-watermark";

    /**
     * 水位安全回退毫秒（应用与 DB 时钟微偏差/秒级精度边界兜底）：
     * 回退多捞的变更重复 ZADD 同分覆盖，幂等无副作用
     */
    private static final long WATERMARK_SAFETY_BACK_MS = 5000L;

    /** 榜 ZSet TTL（分钟）：≥2× 灌榜周期；任务连续异常死亡后榜自然过期，/feed 自动降级时间倒序 */
    private static final long RANK_EXPIRE_MINUTES = 30;

    /** 灌榜分布式锁键（public：定时任务与异步重建触发共用，保证同一时刻仅一个实例/线程灌榜） */
    public static final String REFILL_LOCK_KEY = CacheConstant.LOCK_PREFIX + "content:feed:hot:refill";

    /** 热度配置（权重/衰减参数外置 yml） */
    private final ContentProperties contentProperties;

    /** 文章 Mapper（灌榜候选批量查询） */
    private final ArticleMapper articleMapper;

    /** Redis 工具（ZSet 灌榜/取榜/换榜/剔除成员） */
    private final RedisUtil redisUtil;

    /** 分布式锁（P7 异步重建与定时灌榜互斥，防多实例并发灌榜互踩） */
    private final DistributedLock distributedLock;

    /** P7 异步重建排队标记（CAS 去抖：榜缺失风暴期多个请求只入队一次重建） */
    private final AtomicBoolean rebuildQueued = new AtomicBoolean(false);

    /** P7 异步重建单线程池（daemon：不阻塞 JVM 退出；重建内部自带分布式锁与 fail-safe） */
    private final ExecutorService rebuildExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feed-hot-rank-rebuild");
        t.setDaemon(true);
        return t;
    });

    /**
     * 计算单篇文章热度分（纯公式，无外部依赖，social 落地后可直接复用）
     *
     * 分子：互动热度（越大越好）
     * 互动越多分越高，发得越早分越低。
     * positive = 点赞 × 点赞权重
     *          + 评论 × 评论权重
     *          + 浏览 × 浏览权重
     *
     * ⚠️ 当前 social 未落地，`like_cnt/comment_cnt` 恒 0， 公式实际退化为“纯浏览量 + 时间衰减”排序 ，链路先行跑通。
     * 
     *分母：时间衰减（越久越小，但会让分数变低）
     * hours = 当前时间 − 发布时间（小时）
     * score = positive / (hours + 偏移) ^ 衰减指数
     *
     * @param viewCnt     浏览量（PV）
     * @param likeCnt     点赞数（social 落地前恒 0）
     * @param commentCnt  评论数（social 落地前恒 0）
     * @param publishTime 发布时间
     * @return 热度分（越大越靠前；发布越久衰减越重）
     */
    public double computeScore(long viewCnt, long likeCnt, long commentCnt, LocalDateTime publishTime) {
        ContentProperties.Hot hot = contentProperties.getFeed().getHot();
        // 分子：三类互动信号加权求和（浏览量权重远低于互动，防刷 PV 扰动排名）
        double positive = likeCnt * hot.getLikeWeight()
                + commentCnt * hot.getCommentWeight()
                + viewCnt * hot.getViewWeight();
        // 分母：发布小时数 + 偏移，再取衰减指数次幂（偏移防新文章分母趋 0 分数爆炸）
        long hours = Math.max(Duration.between(publishTime, LocalDateTime.now()).toHours(), 0);
        return positive / Math.pow(hours + hot.getHourOffset(), hot.getDecayExponent());
    }

    /**
     * 全量重算热度榜并写入 ZSet（由灌榜定时任务 FeedHotRankRefillJob 调用）
     *
     * 每隔一段时间，把全站“值得推荐”的文章重新算一遍热度分，选出 Top N，原子替换到 Redis 热度榜里，供 /feed 读取。
     *DB 查候选 → 逐篇算分 → 内存裁剪 Top N → 写影子键 → 原子换榜 → 重置 TTL
     *
     * 实现口径：
     * ① 批量查 zone 1/2 + status=2 + visibility=1 + 未软删文章（含三冗余计数与发布时间）；
     * ② 逐篇 computeScore，按分数倒序仅保留 top N 入榜——榜容量裁剪在内存完成
     *    （替代 ZREMRANGEBYRANK，防 ZSet 无界增长的同时省一次 Redis 修剪往返）；
     * ③ 先写影子键（staging）再 rename 原子换榜——灌榜期间读方无空榜窗口，
     *    全量重算覆盖旧分，已下架/转私密文章自然不写=出榜；
     * ④ 换榜后重置 TTL：任务连续异常死亡超期后榜自动过期，/feed 降级时间倒序兜底。
     *
     * @return 本轮写入榜的文章数
     */
    public int refillHotRank() {
        // 步骤1：批量查榜内候选（纯列查询无分页，候选=全量公开已发布文章；基础条件与增量轮共用同一 wrapper）
        List<Article> articles = articleMapper.selectList(rankCandidateQuery());
        if (articles.isEmpty()) {
            return 0;
        }
        // 步骤2：批量算分（social 未开发 like/comment 恒 0，公式暂退化为纯浏览量）并倒序保留 top N
        ContentProperties.Hot hot = contentProperties.getFeed().getHot();
        Map<Object, Double> top = computeScores(articles).entrySet().stream()
                .sorted(Map.Entry.<Object, Double>comparingByValue().reversed())
                .limit(hot.getTopN())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
        // 步骤3：影子键灌榜 + rename 原子换榜（任一步失败保留旧榜，fail-safe 不清空线上榜单）
//        删旧影子键        ZADD 批量写入 staging        rename staging → 正式榜

        // 删旧影子键
      //影子键就是一个“临时草稿本”，先在草稿上写完，确认没问题再一次性替换正式榜。
        String stagingKey = CacheConstant.CONTENT_FEED_HOT_ZSET + STAGING_SUFFIX;
        redisUtil.delete(stagingKey);

        //ZADD 批量写入 staging
        //staging = 预发布 / 暂存 / 草稿区      在此处场景，staging 就是影子键
        if (redisUtil.zAddBatch(stagingKey, top) < 0) {
            log.warn("热度榜灌榜失败（ZADD 异常，保留旧榜）：stagingKey={}", stagingKey);
            redisUtil.delete(stagingKey);
            return 0;
        }

        //rename staging → 正式榜
        // rename 是 Redis 的一条原子命令，作用是“把影子键改名成正式榜”，一步完成换榜。
        if (!redisUtil.rename(stagingKey, CacheConstant.CONTENT_FEED_HOT_ZSET)) {
            log.warn("热度榜换榜失败（rename 异常，保留旧榜）：stagingKey={}", stagingKey);
            redisUtil.delete(stagingKey);
            return 0;
        }
        // 步骤4：换榜成功后重置 TTL（灌榜周期内任务死亡自动过期，降级时间倒序兜底）
        redisUtil.expire(CacheConstant.CONTENT_FEED_HOT_ZSET, RANK_EXPIRE_MINUTES, TimeUnit.MINUTES);
        return top.size();
    }


    /**
     * 灌榜编排入口（由 FeedHotRankRefillJob 每轮调用）：按榜/水位状态自动选择全量或增量。
     *
     * 三分支：
     * ① 正式榜缺失（TTL 过期/Redis 重启）→ 全量重建（防增量 ZADD 造出只含变更文章的残缺榜）；
     * ② 距上次全量 ≥ fullRefillIntervalMs（默认 1h）→ 全量重算兜底分数衰减收敛
     *    （增量只升不降：存量成员分数随时间应衰减，靠全量轮覆盖校正）；
     * ③ 其余轮次 → 增量灌榜（只捞 stat_update_time ≥ 增量水位的变更文章重算 ZADD）。
     *
     * 增量可行性依据（批次E P2 定案 D-1 2026-11-03）：article.stat_update_time 仅由热度信号
     * （浏览/点赞/评论计数回写）刷新，热度分恰是这三类计数的函数——该列变更 ⇒ 分数可能变化，
     * 增量水位可完整覆盖全部热度信号变更源（P1 热度榜增量优化 2026-10-28）。
     *
     * @return 本轮入榜/更新文章数（失败返回 0，旧榜保留）
     */
    public int refillAuto() {
        // 分支①：正式榜缺失 → 全量重建（内部成功后重置榜 TTL 与两级水位）
        if (!redisUtil.hasKey(CacheConstant.CONTENT_FEED_HOT_ZSET)) {
            return refillFullAndMark(); 
        }
        // 分支②：全量水位缺失/到期 → 全量校正（衰减收敛）
        //lastFull:水位毫秒值（键缺失/值非法返回 null，由调用方走全量兜底）
        Long lastFull = readWatermark(CacheConstant.CONTENT_FEED_HOT_ZSET + FULL_WATERMARK_SUFFIX);
        if (lastFull == null
                || System.currentTimeMillis() - lastFull
                >= contentProperties.getFeed().getHot().getFullRefillIntervalMs()) {
            return refillFullAndMark();
        }
        // 分支③：常规增量轮
        //refillDelta:本轮 ZADD 的变更文章数（0=无变更或失败留痕）
        return refillDelta();
    }

    /**
     * 增量灌榜：只捞 stat_update_time ≥ 增量水位的变更文章重算分数 ZADD 到现有榜，水位前推。
     *
     * 与全量轮的差异：
     * ① 查询范围=热度信号变更 delta（浏览/点赞/评论计数回写），DB 压力随变更量而非存量走；
     * ② 不做换榜（不清存量成员）——增量只升不降，下架/转私密文章的出榜剔除
     *    靠下架路径显式 ZREM（removeFromRank，批次E P2 定案 D-1）+ 展示前回查双保险；
     * ③ 分数衰减靠低频全量轮收敛（refillAuto 分支②）。
     *
     * @return 本轮 ZADD 的变更文章数（0=无变更或失败留痕）
     */
    public int refillDelta() {
        
        // 步骤1：读增量水位；缺失（首次/键被清）→ 全量重建补齐榜与水位（防增量造残缺榜）
        String deltaKey = CacheConstant.CONTENT_FEED_HOT_ZSET + DELTA_WATERMARK_SUFFIX;
        //读水位键
        Long watermark = readWatermark(deltaKey);
        if (watermark == null) {
            return refillFullAndMark();
        }

        // 步骤2：捞变更文章（榜候选基础条件 + stat_update_time ≥ 水位；含 5s 安全回退兜时钟偏差，
        // 多捞的变更重复 ZADD 同分覆盖幂等无害；批次E P1 定案 D-2：走 idx_stat_update_time 单列索引）
         //把 Redis 里存的水位时间戳（毫秒），转成本地时区的 LocalDateTime，用于数据库时间条件查询。
            //watermarkTime:增量灌榜的时间水位线
            // “从这个时间点之后，所有热度信号发生变化的文章，都要被重新算分、重新入榜。”
         LocalDateTime watermarkTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(watermark - WATERMARK_SAFETY_BACK_MS), ZoneId.systemDefault());
        List<Article> changed = articleMapper.selectList(
                rankCandidateQuery().ge(Article::getStatUpdateTime, watermarkTime));

        // 步骤3：算分 ZADD 到现有榜 + 裁剪保 TopN（ZADD 失败则水位不推，下轮重捞）
        if (!changed.isEmpty()) {
            if (redisUtil.zAddBatch(CacheConstant.CONTENT_FEED_HOT_ZSET, computeScores(changed)) < 0) {
                log.warn("热度榜增量灌榜失败（ZADD 异常，水位保留下轮重捞）：changed={}", changed.size());
                return 0;
            }
            trimToTopN();
        } 
        // 步骤4：水位前推为本轮时刻（空变更也要推，防同刻永久重捞）+ 榜 TTL 续期（任务活着榜不过期）
        //“任务活着”   = 定时灌榜任务还在正常跑，没挂、没停、没卡死。  (任务：FeedHotRankRefillJob（定时灌榜任务）)
            //把“增量水位”更新为“当前时间”
        redisUtil.set(deltaKey, System.currentTimeMillis());
            //给线上热度榜续命 30 分钟 (重置TTL)
        redisUtil.expire(CacheConstant.CONTENT_FEED_HOT_ZSET, RANK_EXPIRE_MINUTES, TimeUnit.MINUTES);
        return changed.size();
    }

    /**
     * 全量重灌 + 两级水位前推：仅全量有入榜（count>0）才推——失败下轮继续走全量兜底重试，
     * 空站场景（候选恒空）多几次空全量查询成本可忽略
     *
     * @return 本轮入榜文章数
     */
    private int refillFullAndMark() {
        int count = refillHotRank();
        if (count > 0) {
            // 全量成功：全量水位前推（计衰减校正周期）+ 增量水位前推（全量已覆盖全部成员，防下轮增量重捞历史变更）
            long now = System.currentTimeMillis();
            redisUtil.set(CacheConstant.CONTENT_FEED_HOT_ZSET + FULL_WATERMARK_SUFFIX, now);
            redisUtil.set(CacheConstant.CONTENT_FEED_HOT_ZSET + DELTA_WATERMARK_SUFFIX, now);
        }
        return count;
    }

    /**
     * 组装榜候选基础查询条件（全量/增量共用）：zone 1/2 + 已发布 + 公开 + 未软删，
     * 只取算分所需列（id/三冗余计数/发布时间，不带正文大字段）
     *
     * @return 基础条件 wrapper（调用方追加水位等专属条件）
     */
    private LambdaQueryWrapper<Article> rankCandidateQuery() {
        return new LambdaQueryWrapper<Article>()
                .in(Article::getZone, ZoneEnum.NORMAL, ZoneEnum.WRITER)
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .select(Article::getId, Article::getViewCnt, Article::getLikeCnt,
                        Article::getCommentCnt, Article::getCreateTime);
    }

    /**
     * 批量算分（全量/增量共用）：逐篇 computeScore，成员统一字符串化文章ID
     * （灌榜以字符串写入，兼容各序列化器回读类型差异，与 topArticleIds 解析口径一致）
     *
     * @param articles 候选文章列表
     * @return ZSet 成员→分数映射
     */
    private Map<Object, Double> computeScores(List<Article> articles) {
        Map<Long, Double> all = new HashMap<>(articles.size());
        for (Article article : articles) {
            double score = computeScore(
                    article.getViewCnt() == null ? 0 : article.getViewCnt(),
                    article.getLikeCnt() == null ? 0 : article.getLikeCnt(),
                    article.getCommentCnt() == null ? 0 : article.getCommentCnt(),
                    article.getCreateTime());
            all.put(article.getId(), score);
        }
        return all.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> (Object) String.valueOf(entry.getKey()), Map.Entry::getValue,
                        (a, b) -> a));
    }

    /**
     * 榜容量裁剪（增量轮专用）：按当前分数移除榜尾溢出成员，保持 TopN 有界
     * <p>全量轮在内存裁剪（影子键整榜重建）；增量轮 ZADD 进现有榜后用 ZREMRANGEBYRANK 收口，
     * 防低分变更文章挤入榜导致 ZSet 无界增长</p>
     */
    private void trimToTopN() {
        int topN = contentProperties.getFeed().getHot().getTopN();
        Long size = redisUtil.zSize(CacheConstant.CONTENT_FEED_HOT_ZSET);
        if (size == null || size <= topN) {
            return;
        }
        // 升序排名 [0, size-topN-1] 区间=榜尾低分溢出成员，移除后榜恰好剩 TopN 个
        redisUtil.zRemoveRangeByRank(CacheConstant.CONTENT_FEED_HOT_ZSET, 0, size - topN - 1);
    }

    /**
     * 读水位键（epoch millis）
     *
     * @param key 水位键
     * @return 水位毫秒值（键缺失/值非法返回 null，由调用方走全量兜底）
     */
    private Long readWatermark(String key) {
        Object value = redisUtil.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            // 水位值脏数据（人工误写/序列化异常）：走全量兜底重建，不阻断灌榜任务
            log.warn("灌榜水位值非法（走全量兜底重建）：key={}, value={}", key, value);
            return null;
        }
    }

    /**
     * 按热度分倒序分页取文章 ID（阶段14 切流后 /feed 主数据源）
     *
     * @param pageNo   页码（1 起）
     * @param pageSize 页大小
     * @return 榜上文章 ID 列表（榜空/缺页返回空列表，调用方降级时间倒序直查兜底）
     */
    public List<Long> topArticleIds(int pageNo, int pageSize) {
        // 步骤1：ZREVRANGE 按分数倒序取本页成员（offset=(pageNo-1)*pageSize，count=页大小）
        List<Object> members = redisUtil.zReverseRangePage(CacheConstant.CONTENT_FEED_HOT_ZSET,
                (long) (pageNo - 1) * pageSize, pageSize);
        // 步骤2：成员统一转 Long（灌榜时以字符串写入，兼容各序列化器回读类型差异）
        return members.stream()
                .map(member -> Long.parseLong(String.valueOf(member)))
                .toList();
    }

    /**
     * 从热度榜实时剔除单篇文章（批次E P2 定案 D-1 2026-11-03）。
     *
     * 作用：
     * 下架/转私密/软删后立即把该文章移出 ZSet 热度榜——增量灌榜只升不降、
     * 靠时间列捞不到「成员消失」，若不显式剔除，已下架文章会在榜上残留最长
     * 一个全量轮周期（默认 1h）。fail-safe：Redis 异常仅 warn 不阻断下架主流程
     * （残留成员由展示前回查 recheckFilter 过滤兜底，下轮全量重灌自然消失）。
     * 调用点统一收口在 ContentEventProducer#sendUnpublished（下线四路径）。
     *
     * @param articleId 文章ID
     */
    public void removeFromRank(Long articleId) {
        try {
            // 按成员值剔除（灌榜以字符串写入，剔除口径保持一致）；Redis 不可用时吞异常走兜底
            redisUtil.zRemove(CacheConstant.CONTENT_FEED_HOT_ZSET, String.valueOf(articleId));
        } catch (Exception e) {
            // fail-safe：剔除失败不阻断下架链路（展示回查兜底 + 全量重灌自愈）
            log.warn("热度榜剔除成员失败（不阻断下架，展示回查兜底）：articleId={}", articleId, e);
        }
    }

    
    /**
     * 榜缺失时异步触发重建（批次E P7）：不等下一个灌榜定时周期（默认 5min）。
     *
     * 作用：
     * /feed 读榜发现榜空时调用——前置判断 refill-enabled 灌榜开关（E.4 补漏②：开关关闭的实例
     * 与定时任务同一口径不灌榜）；CAS 去抖（同一时刻只允许排队一次，防榜缺失风暴期
     * 每个请求都触发重建）；单线程池异步执行，与定时灌榜共用同一把分布式锁
     * （REFILL_LOCK_KEY，抢不到说明定时任务/他实例正在灌，直接放弃本轮）。
     * 触发时机由调用方收口在「第 1 页榜空」（E.4 补漏①：榜非空、深页超界不触发）。
     *
     * <p>与定时任务的关系：定时灌榜是兜底主路径，本方法只是把「榜缺失后最长等 5min」
     * 的空窗压缩到秒级；重建本身复用 refillAuto（全量重建 + 水位重置）。</p>
     */
    public void triggerRebuildAsync() {
        // 前置：灌榜开关判断（E.4 补漏②）——refill-enabled 关闭的实例定时任务不灌、异步触发同样不灌
        if (!contentProperties.getFeed().getHot().isRefillEnabled()) {
            return;
        }
        // 去抖：已排队未执行完的重建不重复入队（榜缺失期间大量并发请求只触发一次）
        if (!rebuildQueued.compareAndSet(false, true)) {
            //compareAndSet:如果当前值 == expectedValue，就原子地把它改成 newValue，并返回 true；否则什么都不做，返回 false。
            //这是 CAS（Compare-And-Swap）​ 操作，是线程安全的。
            return; 
        }
        rebuildExecutor.submit(() -> {
            try {
                // 与定时灌榜互斥：抢不到锁（他实例/定时任务正在灌）直接放弃，fixedDelay 不堆积
                if (!distributedLock.tryLock(REFILL_LOCK_KEY, 0, TimeUnit.SECONDS)) {
                    return;
                }
                try {
                    int count = refillAuto();
                    if (count > 0) {
                        log.info("热度榜异步重建完成：articles={}", count);
                    }
                } finally {
                    distributedLock.unlock(REFILL_LOCK_KEY);
                }
            } catch (Exception e) {
                // fail-safe：异步重建失败不等告警，下一个定时周期自然重试
                log.warn("热度榜异步重建失败（fail-safe，等下一轮定时兜底）", e);
            } finally {
                // 执行完放开排队标记，后续榜缺失可再次触发
                rebuildQueued.set(false);
            }
        });
    }

    /**
     * 释放异步重建线程池（容器关闭时调用，daemon 线程本身不阻塞退出，此处仅做优雅关闭）
     */
    @jakarta.annotation.PreDestroy
    public void shutdownRebuildExecutor() {
        rebuildExecutor.shutdownNow();
    }
}
