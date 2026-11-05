package com.litisle.litislecontent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.domain.dto.FeedProfile;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 推荐画像聚合（批次十 10.3：P0 只用 reading_history 聚合「常看分区 + 常看分类 + 已读集合」）
 *
 * <p>P0 边界（对齐 next_update 已确认决策）：
 * <ul>
 *   <li>不读 article_tag（标签偏好进 P1）；</li>
 *   <li>不读收藏（10.5 再补 favorite 维度与 Redis 画像缓存）；</li>
 *   <li>匿名用户没有 reading_history，直接返回冷启动画像。</li>
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

    /** 推荐流配置（画像样本条数/已读窗口外置 yml） */
    private final ContentProperties contentProperties;

    /**
     * 加载用户画像（文章流与书籍流共用）
     *
     * @param userId 登录用户 ID；匿名用户传 null
     * @return 画像对象（无行为数据时 coldStart=true，个性化召回路退化全局）
     */
    public FeedProfile load(Long userId) {
        // 匿名用户没有阅读历史：直接返回冷启动画像，不发一次空查询
        if (userId == null) {
            return new FeedProfile();
        }
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
