package com.litisle.litislecontent.service.support;

import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.domain.entity.ArticleStat;
import com.litisle.litislecontent.domain.entity.BookStat;
import com.litisle.litislecontent.domain.entity.ContentStatWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 推荐流打分支撑（批次十一 11.10：文章/书籍唯一打分入口，热门榜与推荐流共用）
 *
 * <p>为什么单独收口：11.10 之前热度榜与推荐流各自算分（热门榜用 Hacker News 衰减公式、
 * 推荐流用另一套饱和公式），同一篇文章在两处分数不一致。本类只保留一套公式：</p>
 * <ul>
 *   <li>统计量平滑：统一 {@code (x + m*prior)/(x+m)}，文章四个统计量与书籍两个统计量共用同一 m/prior；</li>
 *   <li>文章总分：收藏/点赞/评论/浏览 + 新鲜度 + 分区/分类匹配，各权重读 yml {@code article-weights}；</li>
 *   <li>书籍总分：收藏/阅读人数 + 新鲜度 + 完结偏好，权重读 yml {@code book-weights}（结构保持不变）；</li>
 *   <li>时间衰减：统一由 {@link #freshScore} 的半衰期承担，不再有第二套衰减公式。</li>
 * </ul>
 *
 * <p>热门榜（{@link FeedHotRankSupport}）调用时分区/分类匹配传 0（全局榜无用户画像），
 * 推荐流（{@code FeedStreamServiceImpl}）传入画像命中结果；同一输入下两者分数完全一致。</p>
 *
 * <p>真源说明：统计量全部来自 {@code article_stat} / {@code book_stat}（以及窗口表），
 * 不读 {@code article.like_cnt} 等冗余镜像列——镜像列只用于详情展示与索引文档，不是排序真源。</p>
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class FeedScoreSupport {

    /** content 配置（文章权重、书籍权重、统一平滑参数、新鲜度半衰期、统计窗口） */
    private final ContentProperties contentProperties;

    /**
     * 统一计数平滑（文章与书籍唯一实现）。
     *
     * <p>smoothed = (x + m*prior)/(x+m)：与候选池无关，同一内容在任意池中得分一致。
     * m<=0 属非法配置，退化为「有互动=1、无互动=prior」，避免除零把排序打崩。</p>
     *
     * @param count    原始计数（收藏/点赞/评论/浏览/阅读人数）
     * @param smoothing 统一平滑参数（m/prior）
     * @return 平滑后的 0~1 分值
     */
    public double smoothCount(long count, ContentProperties.Smoothing smoothing) {
        double strength = smoothing.getStrength();
        double prior = smoothing.getPrior();
        // 非法配置防御：m<=0 时无法按公式计算，退化为二值分
        if (strength <= 0) {
            return count > 0 ? 1.0 : prior;
        }
        // 先验收缩：计数越小越接近 prior，越大越接近 1
        return (count + strength * prior) / (count + strength);
    }

    /**
     * 新鲜度分（统一时间衰减，半衰期越长衰减越慢）。
     *
     * @param createTime    内容发布时间（用 create_time 而非 update_time，避免编辑把老内容顶到前排）
     * @param halfLifeHours 新鲜度半衰期（小时；<=0 视为不衰减）
     * @return 0~1 分值
     */
    public double freshScore(LocalDateTime createTime, double halfLifeHours) {
        // 时间为空（异常数据）按最旧处理，不给新鲜度加分
        if (createTime == null) {
            return 0.0;
        }
        // 非法半衰期不做衰减：与旧降级口径一致，避免除零
        if (halfLifeHours <= 0) {
            return 1.0;
        }
        // 发布至今小时数，未来时间按 0 处理（防负值让分数超过 1）
        long hours = Math.max(Duration.between(createTime, LocalDateTime.now()).toHours(), 0L);
        // 半衰期公式：每过 halfLifeHours 分数减半
        return Math.pow(0.5, hours / halfLifeHours);
    }

    /**
     * 文章总分（热门榜与推荐流共用，唯一实现）。
     *
     * @param stat          文章统计行（article_stat 真值，可为 null 表示尚无统计行）
     * @param collectCount  收藏数（优先 30/90 天窗口，窗口缺失时由调用方回退累计值）
     * @param createTime    发布时间（新鲜度）
     * @param zoneMatch     分区匹配分（命中=1/未命中=0；热门榜传 0）
     * @param categoryMatch 分类匹配分（命中=1/未命中=0；热门榜传 0）
     * @return 融合总分
     */
    public double computeArticleScore(ArticleStat stat, long collectCount, LocalDateTime createTime,
                                      double zoneMatch, double categoryMatch) {
        // 取文章流配置：权重 + 统一平滑参数 + 半衰期
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        ContentProperties.ArticleWeights weights = stream.getArticleWeights();
        ContentProperties.Smoothing smoothing = stream.getSmoothing();
        // 统计行缺失时四个统计量按 0（不跳过算分，保证候选都有可比分数）
        long likeCount = stat == null ? 0L : nullToZero(stat.getLikeCount());
        long commentCount = stat == null ? 0L : nullToZero(stat.getCommentCount());
        long viewCount = stat == null ? 0L : nullToZero(stat.getViewCount());
        // 四个统计量分别平滑后按 yml 权重加权
        double collectNorm = smoothCount(collectCount, smoothing);
        double likeNorm = smoothCount(likeCount, smoothing);
        double commentNorm = smoothCount(commentCount, smoothing);
        double viewNorm = smoothCount(viewCount, smoothing);
        // 新鲜度 + 画像偏好项
        double fresh = freshScore(createTime, stream.getFreshHalfLifeHours());
        return weights.getCollect() * collectNorm
                + weights.getLike() * likeNorm
                + weights.getComment() * commentNorm
                + weights.getView() * viewNorm
                + weights.getFresh() * fresh
                + weights.getZone() * zoneMatch
                + weights.getCategory() * categoryMatch;
    }

    /**
     * 书籍总分（保持批次十原有公式结构，只把平滑切到统一参数）。
     *
     * @param stat          书籍统计行（book_stat 真值，可为 null）
     * @param collectCount  收藏数（窗口优先，累计兜底）
     * @param readerCount   阅读人数（窗口优先，累计兜底）
     * @param createTime    发布时间（新鲜度）
     * @param finishMatch   完结偏好匹配分（命中=1/未命中=0）
     * @return 融合总分
     */
    public double computeBookScore(BookStat stat, long collectCount, long readerCount, LocalDateTime createTime,
                                   double finishMatch) {
        // 取书籍流配置与统一平滑参数
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        ContentProperties.BookWeights weights = stream.getBookWeights();
        ContentProperties.Smoothing smoothing = stream.getSmoothing();
        // 两个计数统一平滑（m=5、prior=0.1，与书籍历史默认值相同，分数不变）
        double collectNorm = smoothCount(collectCount, smoothing);
        double readNorm = smoothCount(readerCount, smoothing);
        double fresh = freshScore(createTime, stream.getFreshHalfLifeHours());
        return weights.getCollect() * collectNorm
                + weights.getRead() * readNorm
                + weights.getFresh() * fresh
                + weights.getFinish() * finishMatch;
    }

    /**
     * 窗口计数解析（书籍收藏/阅读人数、文章收藏数共用同一回退链）。
     *
     * <p>顺序：窗口任务关闭 → 累计值；无该目标窗口行 → 累计值；短窗口 → 长窗口 → 累计值。</p>
     *
     * @param windows       目标 ID → 窗口天数 → 窗口行（批量查询结果）
     * @param id            目标 ID
     * @param config        窗口配置（开关与 30/90 天）
     * @param collect       true=取收藏数，false=取阅读人数
     * @param totalFallback 累计值（统计表真值，最终兜底）
     * @return 参与排序的计数
     */
    public long resolveWindowCount(Map<Long, Map<Integer, ContentStatWindow>> windows, Long id,
                                   ContentProperties.Window config, boolean collect, long totalFallback) {
        // 窗口任务关闭或批量结果为空：直接使用累计值
        if (!config.isEnabled() || windows == null) {
            return totalFallback;
        }
        // 该目标没有窗口行：回退累计值
        Map<Integer, ContentStatWindow> byDays = windows.get(id);
        if (byDays == null) {
            return totalFallback;
        }
        // 优先短窗口
        ContentStatWindow shortRow = byDays.get(config.getShortDays());
        if (shortRow != null) {
            return collect ? nullToZero(shortRow.getCollectCount()) : nullToZero(shortRow.getReadCount());
        }
        // 短窗口缺失时用长窗口
        ContentStatWindow longRow = byDays.get(config.getLongDays());
        if (longRow != null) {
            return collect ? nullToZero(longRow.getCollectCount()) : nullToZero(longRow.getReadCount());
        }
        // 两档都缺失：全量兜底
        return totalFallback;
    }

    /**
     * null 计数转 0（统计行字段允许空值，避免拆箱 NPE）。
     *
     * @param value 计数值
     * @return 非空计数
     */
    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }
}
