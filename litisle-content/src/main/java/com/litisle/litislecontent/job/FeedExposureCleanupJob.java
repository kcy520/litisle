package com.litisle.litislecontent.job;

import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.mapper.FeedExposureDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 曝光/点击日聚合过期清理任务（批次十一 11.6）
 *
 * <p>作用：按 {@code litisle.content.feed.exposure.retain-days}（默认 30 天）删除过期的
 * {@code feed_exposure_daily} 行，防止表无界增长。</p>
 *
 * <p>为什么选定时删除而不是表分区：日表规模小、单表足够；分区需要持续的分区维护 DDL 与
 * 分区键约束（唯一键必须包含分区列），维护成本高于收益。删除条件以 stat_date 开头，
 * 命中唯一键最左列，无需额外索引；单批上限 1000 行循环删除，避免一次大删锁表。</p>
 *
 * <p>失败处理：本轮异常只记 WARN 并结束，不阻断应用；下一轮 fixedDelay 继续。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class FeedExposureCleanupJob {

    /** 曝光/点击日聚合 Mapper（分批删除过期行） */
    private final FeedExposureDailyMapper feedExposureDailyMapper;

    /** content 配置（保留天数、清理开关与批量/周期参数） */
    private final ContentProperties contentProperties;

    /**
     * 定时清理入口（周期与首次延迟读 yml，默认每日一次、启动后 5 分钟首跑）。
     */
    @Scheduled(fixedDelayString = "${litisle.content.feed.exposure.cleanup-interval-ms:86400000}",
            initialDelayString = "${litisle.content.feed.exposure.cleanup-initial-delay-ms:300000}")
    public void cleanup() {
        // 步骤1：开关判断（关闭时仅保留手工清理能力，不产生删除开销）
        ContentProperties.Exposure config = contentProperties.getFeed().getExposure();
        if (!config.isCleanupEnabled()) {
            log.info("[曝光清理] 开关关闭，本轮跳过");
            return;
        }
        // 步骤2：保留起点 = 今天 - 保留天数（早于该日期的行删除；保留天数含今天共 N 天）
        LocalDate cutoffDate = LocalDate.now().minusDays(config.getRetainDays());
        long totalDeleted = 0L;
        try {
            int deleted;
            // 步骤3：分批循环删除，直到某批不足批量上限（说明已经删完）
            do {
                deleted = feedExposureDailyMapper.deleteBefore(cutoffDate, config.getCleanupBatchSize());
                totalDeleted += deleted;
            } while (deleted >= config.getCleanupBatchSize());
            log.info("[曝光清理] 完成：cutoff={}, 删除行数={}", cutoffDate, totalDeleted);
        } catch (Exception exception) {
            // 步骤4：异常只告警，不向上抛出（清理失败不影响曝光/点击写入与查询）
            log.warn("[曝光清理] 异常中止（本轮已删除 {} 行）：{}", totalDeleted, exception.getMessage());
        }
    }
}
