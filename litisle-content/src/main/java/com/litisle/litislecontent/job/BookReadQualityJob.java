package com.litisle.litislecontent.job;

import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.mapper.BookReadQualityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 书籍粗完读率统计任务（批次十一 11.13）
 *
 * <p>作用：按日全量重算 book_read_quality 的「完读人数 / 有效阅读人数」，供报表或书籍卡片展示。
 * 推荐流翻页与书籍详情不现算，只读这张预计算表。</p>
 *
 * <p>为什么选全量重算而不是增量：</p>
 * <ul>
 *   <li>判定依赖「该书当前最大可见章节号」，新章节发布会把原本完读的用户改判为未完成；</li>
 *   <li>阅读历史 11.4 会按 90 天裁剪，分母会随时间收缩；</li>
 *   <li>增量只能感知「新增阅读」，无法感知上面两类反向变化，必须全量才能纠正。</li>
 * </ul>
 *
 * <p>更新频率：默认每日一次（fixedDelay）。reading_history 保留窗口有上限，单轮扫描量可控；
 * 如果后续需要更快反馈，可改成每 6 小时，但不要低于小时级（收益低且浪费 DB 扫描）。</p>
 *
 * <p>多实例部署：分布式锁单点执行，抢不到锁直接放弃本轮。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class BookReadQualityJob {

    /** 粗完读率统计 Mapper（单条 SQL 全量 upsert） */
    private final BookReadQualityMapper bookReadQualityMapper;

    /** content 配置（任务开关与调度参数） */
    private final ContentProperties contentProperties;

    /** 分布式锁（多实例单点执行） */
    private final DistributedLock distributedLock;

    /**
     * 定时全量重算书籍粗完读率（周期与首次延迟读 yml，默认每日一次、启动后 10 分钟首跑）。
     */
    @Scheduled(fixedDelayString = "${litisle.content.stat.read-quality.interval-ms:86400000}",
            initialDelayString = "${litisle.content.stat.read-quality.initial-delay-ms:600000}")
    public void refresh() {
        // 步骤1：开关判断（关闭后不产生任何查询开销）
        ContentProperties.ReadQuality config = contentProperties.getStat().getReadQuality();
        if (!config.isEnabled()) {
            log.info("[粗完读率] 开关关闭，本轮跳过");
            return;
        }
        // 步骤2：抢锁（wait=0 不等待；抢不到说明他实例本轮在算，直接放弃）
        if (!distributedLock.tryLock(ContentBizConstant.BOOK_READ_QUALITY_LOCK_KEY, 0, TimeUnit.SECONDS)) {
            return;
        }
        try {
            // 步骤3：全量重算并 upsert（以 book 全表为基准行，无历史的书写 0/0，防旧值残留）
            int rows = bookReadQualityMapper.upsertFull();
            log.info("[粗完读率] 本轮完成：rows={}", rows);
        } catch (Exception e) {
            // 失败只告警：表内保留上一轮值，下一轮全量重算纠正（幂等）
            log.error("[粗完读率] 本轮执行异常，已跳过（保留上一轮统计值）", e);
        } finally {
            distributedLock.unlock(ContentBizConstant.BOOK_READ_QUALITY_LOCK_KEY);
        }
    }
}
