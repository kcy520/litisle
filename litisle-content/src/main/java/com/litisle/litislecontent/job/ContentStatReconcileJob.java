package com.litisle.litislecontent.job;

import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.domain.dto.ArticleCounterDiffDTO;
import com.litisle.litislecontent.mapper.ContentStatReconcileMapper;
import com.litisle.litislecontent.metrics.ContentMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 互动统计对账任务（批次十一 11.16）
 *
 * <p>作用：定时比对 article_stat/book_stat 与源表聚合（favorite、article、reading_history），
 * 发现差异写日志并按类型打指标；本任务<b>只观察不自动修正</b>，修正策略另行确定。</p>
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>收藏、文章浏览量/点赞数/评论数做双向差异（含统计表残留行）；</li>
 *   <li>书籍阅读人数只查「源表大于统计表」的少计方向——reading_history 按「90 天 + 单用户 1000 条」
 *       裁剪后源表天然偏小（批次十一 11.4 起），要求与统计表相等会持续误报；</li>
 *   <li>chapter_count 暂不参与对账：11.2 已接入挂章/移除章节的同步写入，对账维度留待后续补充。</li>
 * </ul>
 *
 * <p>失败处理：单轮查询异常只记录 error 日志并跳过本轮，不阻断应用；下一轮 fixedDelay 继续。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ContentStatReconcileJob {

    /** 对账差异查询 Mapper（只读，不修正数据） */
    private final ContentStatReconcileMapper reconcileMapper;

    /** content 观测指标注册入口（对账差异 Gauge） */
    private final ContentMetrics contentMetrics;

    /** content 配置（对账开关与差异阈值） */
    private final ContentProperties contentProperties;

    /**
     * 定时对账入口（周期与首次延迟读 yml，默认每日一次、启动后 5 分钟首跑）。
     */
    @Scheduled(fixedDelayString = "${litisle.content.stat.reconcile.interval-ms:86400000}",
            initialDelayString = "${litisle.content.stat.reconcile.initial-delay-ms:300000}")
    public void reconcile() {
        // 步骤1：开关判断（关闭时仅保留手工触发能力，不产生查询开销）
        ContentProperties.Reconcile config = contentProperties.getStat().getReconcile();
        if (!config.isEnabled()) {
            log.info("[统计对账] 开关关闭，本轮跳过");
            return;
        }
        try {
            // 步骤2：逐类查询差异行数（源表有但统计表不同 + 统计表残留）
            long articleCollectDiff = reconcileMapper.countArticleCollectSourceDiff()
                    + reconcileMapper.countArticleCollectStatOnlyDiff();
            long bookCollectDiff = reconcileMapper.countBookCollectSourceDiff()
                    + reconcileMapper.countBookCollectStatOnlyDiff();
            // 文章浏览量/点赞/评论三项合并为一次查询，避免文章全表被扫三遍
            ArticleCounterDiffDTO articleCounterDiff = reconcileMapper.countArticleCounterDiff();
            long articleViewDiff = articleCounterDiff == null || articleCounterDiff.getViewDiff() == null
                    ? 0L : articleCounterDiff.getViewDiff();
            long articleLikeDiff = articleCounterDiff == null || articleCounterDiff.getLikeDiff() == null
                    ? 0L : articleCounterDiff.getLikeDiff();
            long articleCommentDiff = articleCounterDiff == null || articleCounterDiff.getCommentDiff() == null
                    ? 0L : articleCounterDiff.getCommentDiff();
            long bookReaderUnderDiff = reconcileMapper.countBookReaderUnderDiff();
            // 单轮总差异行数：用于与配置阈值比较，决定日志级别
            long totalDiff = articleCollectDiff + bookCollectDiff + articleViewDiff
                    + articleLikeDiff + articleCommentDiff + bookReaderUnderDiff;

            // 步骤3：按差异类型写指标（Gauge 覆盖为「最近一轮值」，0 也写入，保证 6 条时间线常驻）
            contentMetrics.recordStatReconcileDiff(ContentMetrics.STAT_TYPE_ARTICLE_COLLECT, articleCollectDiff);
            contentMetrics.recordStatReconcileDiff(ContentMetrics.STAT_TYPE_BOOK_COLLECT, bookCollectDiff);
            contentMetrics.recordStatReconcileDiff(ContentMetrics.STAT_TYPE_ARTICLE_VIEW, articleViewDiff);
            contentMetrics.recordStatReconcileDiff(ContentMetrics.STAT_TYPE_ARTICLE_LIKE, articleLikeDiff);
            contentMetrics.recordStatReconcileDiff(ContentMetrics.STAT_TYPE_ARTICLE_COMMENT, articleCommentDiff);
            contentMetrics.recordStatReconcileDiff(ContentMetrics.STAT_TYPE_BOOK_READER_UNDER, bookReaderUnderDiff);

            // 步骤4：超阈值按 error 输出（供日志告警规则匹配），否则 info 留痕
            if (totalDiff > config.getDiffRowsThreshold()) {
                log.error("[统计对账] 差异超阈值：total={}, threshold={}, articleCollect={}, bookCollect={}, "
                                + "articleView={}, articleLike={}, articleComment={}, bookReaderUnder={}",
                        totalDiff, config.getDiffRowsThreshold(), articleCollectDiff, bookCollectDiff,
                        articleViewDiff, articleLikeDiff, articleCommentDiff, bookReaderUnderDiff);
            } else {
                log.info("[统计对账] 完成：total={}, threshold={}, articleCollect={}, bookCollect={}, "
                                + "articleView={}, articleLike={}, articleComment={}, bookReaderUnder={}",
                        totalDiff, config.getDiffRowsThreshold(), articleCollectDiff, bookCollectDiff,
                        articleViewDiff, articleLikeDiff, articleCommentDiff, bookReaderUnderDiff);
            }
        } catch (Exception e) {
            // 对账异常只告警：查询失败不影响业务写入，下一轮继续
            log.error("[统计对账] 本轮执行异常，已跳过（不修正任何数据）", e);
        }
    }
}
