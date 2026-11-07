package com.litisle.litislecontent.job;

import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.mapper.ContentStatWindowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 互动统计窗口日更任务（批次十一 11.3）
 *
 * <p>作用：按配置周期（默认每日一次）把「收藏/阅读 30 天、90 天窗口」全量重算到
 * content_stat_window。推荐流翻页只读预计算值，查询期不再对 favorite / reading_history 做聚合。</p>
 *
 * <p>计算方式（全量重算，不是增量滑动）：</p>
 * <ul>
 *   <li>每轮以 article/book 现存行作为基准行，left join 窗口内聚合后 upsert；</li>
 *   <li>全量重算简单、天然幂等，窗口内「取消收藏/历史被裁剪」都能被下一轮自然纠正；
 *       代价是每轮扫描 favorite / reading_history 的窗口内行——数据量按内容规模而非交互次数增长，
 *       当前规模可接受；若后续数据量变大，再改增量滑动（记录上次重算水位）；</li>
 *   <li>阅读人数受阅读历史裁剪约束：11.4 保留 90 天，因此 90 天窗口准确；窗口天数超过保留天数会偏小，
 *       配置时不要超过阅读历史的 retain-days。</li>
 * </ul>
 *
 * <p>多实例部署：靠分布式锁单点执行，抢不到锁直接放弃本轮（fixedDelay 不堆积）。
 * 失败只告警，不阻断应用；下一轮继续全量重算（幂等）。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class ContentStatWindowJob {

    /** 窗口任务分布式锁键（多实例单点执行，防并发重复重算） */
    private static final String WINDOW_LOCK_KEY = CacheConstant.LOCK_PREFIX + "content:stat:window";

    /** 统计窗口 Mapper（文章/书籍窗口 upsert 写入入口） */
    private final ContentStatWindowMapper contentStatWindowMapper;

    /** content 配置（窗口开关与窗口天数） */
    private final ContentProperties contentProperties;

    /** 分布式锁（多实例单点执行） */
    private final DistributedLock distributedLock;

    /**
     * 定时重算窗口统计（周期与首次延迟读 yml，默认每日一次、启动后 6 分钟首跑）。
     */
    @Scheduled(fixedDelayString = "${litisle.content.stat.window.interval-ms:86400000}",
            initialDelayString = "${litisle.content.stat.window.initial-delay-ms:360000}")
    public void refresh() {
        // 步骤1：开关判断（关闭后不产生任何查询开销）
        ContentProperties.Window config = contentProperties.getStat().getWindow();
        if (!config.isEnabled()) {
            log.info("[统计窗口] 开关关闭，本轮跳过");
            return;
        }
        // 步骤2：抢锁（wait=0 不等待；抢不到说明他实例本轮在算，直接放弃）
        if (!distributedLock.tryLock(WINDOW_LOCK_KEY, 0, TimeUnit.SECONDS)) {
            return;
        }
        try {
            // 步骤3：整理窗口天数集合（short==long 时只算一次，避免同一窗口重复 upsert）
            Set<Integer> windowDays = new LinkedHashSet<>();
            windowDays.add(config.getShortDays());
            windowDays.add(config.getLongDays());
            // 单轮总写入行数（仅用于日志留痕）
            int totalRows = 0;
            for (Integer days : windowDays) {
                // 窗口起点：当前时间减窗口天数（favorite 用 create_time、reading_history 用 update_time 比较）
                LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
                // 文章窗口：收藏 + 阅读人数
                int articleRows = contentStatWindowMapper.upsertArticleWindow(days, cutoff);
                // 书籍窗口：收藏 + 阅读人数
                int bookRows = contentStatWindowMapper.upsertBookWindow(days, cutoff);
                totalRows += articleRows + bookRows;
                log.info("[统计窗口] 窗口完成：days={}, articleRows={}, bookRows={}", days, articleRows, bookRows);
            }
            log.info("[统计窗口] 本轮完成：windows={}, totalRows={}", windowDays, totalRows);
        } catch (Exception e) {
            // 失败只告警：窗口表保留上一轮值，下一轮全量重算纠正
            log.error("[统计窗口] 本轮执行异常，已跳过（保留上一轮窗口值）", e);
        } finally {
            distributedLock.unlock(WINDOW_LOCK_KEY);
        }
    }
}
