package com.litisle.litislecontent.job;


import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.event.content.ContentViewCountFlushedEvent;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mq.ContentEventProducer;
import com.litisle.litislecontent.service.support.ContentStatSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 浏览计数定时刷回任务（阶段8①：view_cnt 异步回刷兑现，§8-①）。
 *
 * 作用：
 * SCAN 遍历 view:pending:* 待刷回键 → GETDEL 原子取出增量（防刷回期间新增计数丢失）→
 * ArticleMapper#incrViewCnt SQL 层原子自增回写（禁先查后写，并发丢更新）。
 * 定位对齐 TagCountReconcileJob：主链路正确性来自 INCR 累计，本任务仅合并回写；
 * 刷回失败增量已在 GETDEL 时取出会丢——无补偿回写（阶段10 决策：接受丢弃，不做 INCR 回退）：
 * 回写失败/rows=0 直接 continue（丢弃留痕）；不做 GET 前读+SET 回写补偿的原因：
 * GET 与回写间 ViewCounterSupport 可能又 INCR 新增量，回写覆盖反而多丢，加锁/Lua 过度复杂化
 * ——浏览量是非账务热度信号（PV+去重窗口本就近似值），丢一轮窗口可容忍，与「rows=0
 * 软删文增量丢弃」同一 fail-safe 口径。
 *
 * 批次B B7 发布点接入：每篇刷回成功后补推 `content.view-count-flushed`（值=落库后最新绝对值，
 * 覆盖语义非增量），供 search 侧累加 Redis 热度待刷新键（由 IndexHeatFlushJob 定时批量重算 heat，
 * 不直写 ES——批次A A7）；定时任务无事务包裹，落库即提交，无需 afterCommit 钩子。
 *
 * @author litisle
 */
@Component
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class ViewCountFlushJob {

    /** 字符串 Redis 模板（SCAN/GETDEL 载体） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 文章 Mapper（view_cnt 原子自增回写） */
    private final ArticleMapper articleMapper;

    /** content 域 MQ 事件发送统一收口（刷回成功后补推 content.view-count-flushed，批次B B7） */
    private final ContentEventProducer contentEventProducer;

    /** 互动统计写入组件（批次十一 11.1：浏览量绝对值同步到 article_stat） */
    private final ContentStatSupport contentStatSupport;

    /** 刷回开关（默认开；多实例部署时建议只开一台或改用分布式锁，对齐 TagCountReconcileJob） */
    @Value("${litisle.content.view.flush.enabled:true}")
    private boolean enabled;

    /**
     * 定时刷回浏览计数。
     *
     * 作用：按配置周期（默认 5min，启动延迟 1min）SCAN pending 键逐个 GETDEL → 原子自增回写；
     * 受 view.flush.enabled 开关控制；回写 rows=0（文章已删）直接丢弃增量（软删文计数无语义）。
     */
    @Scheduled(fixedDelayString = "${litisle.content.view.flush.interval-ms:300000}",
            initialDelayString = "${litisle.content.view.flush.initial-delay-ms:60000}")
    public void flush() {
        // 步骤1：开关判断（多实例部署单点开启）
        if (!enabled) {
            return;
        }
        // 步骤2：SCAN 遍历待刷回键（禁 KEYS 阻塞；COUNT 适度摊薄单轮扫描压力）
        long totalDelta = 0;
        int articleCount = 0;
        // 本轮刷回成功的文章 → 落库后最新浏览量（循环结束后统一补推事件，避免事件发送阻塞 SCAN 游标）
        Map<Long, Long> flushedViews = new LinkedHashMap<>();
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions().match(ContentBizConstant.VIEW_CNT_PENDING_KEY_PREFIX + "*").count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                // 步骤3：GETDEL 原子取出（防取出与刷回间隙新增计数丢失）
                String deltaStr = stringRedisTemplate.opsForValue().getAndDelete(key);
                if (deltaStr == null) {
                    continue;
                }
                long delta;
                try {
                    delta = Long.parseLong(deltaStr);
                } catch (NumberFormatException e) {
                    log.warn("浏览计数键值非法（丢弃留痕）：key={}, value={}", key, deltaStr);
                    continue;
                }
                if (delta <= 0) {
                    continue;
                }
                // 步骤4：解析 articleId 并原子回写（rows=0=文章不存在/已软删，增量直接丢弃）
                Long articleId = parseArticleId(key);
                if (articleId == null) {
                    continue;
                }
                int rows = articleMapper.incrViewCnt(articleId, delta);
                if (rows == 0) {
                    log.info("浏览计数刷回跳过（文章不存在或已软删，增量丢弃）：articleId={}, delta={}", articleId, delta);
                    continue;
                }
                totalDelta += delta;
                articleCount++;
                // 步骤4.5：取落库后的最新绝对值备用（事件契约为覆盖语义，禁传增量）；
                // 单列查询避开 content_html 大字段；查不到（并发软删）跳过事件，不影响本轮回写
                Long latestViewCnt = articleMapper.selectViewCnt(articleId);
                if (latestViewCnt != null) {
                    flushedViews.put(articleId, latestViewCnt);
                    // 批次十一 11.1：浏览量同步到 article_stat（覆盖写最新绝对值）；
                    // 浏览量属非账务热度信号，同步失败只告警，不回滚已完成的 article 回写
                    try {
                        contentStatSupport.syncViewCount(articleId, latestViewCnt);
                    } catch (Exception e) {
                        log.warn("浏览统计同步失败（统计表不可用时跳过）：articleId={}", articleId, e);
                    }
                }
            }
        } catch (Exception e) {
            // SCAN/GETDEL 异常 fail-safe：本轮终止，增量仍在键中（未 GETDEL 的部分下轮重试）
            log.warn("浏览计数刷回任务异常（fail-safe 下轮重试）", e);
            return;
        }
        // 步骤5：补推浏览量落库事件（批次B B7）：每篇一条、值=落库后绝对值；发送失败由 outbox 兜底，
        // 不影响已完成的 MySQL 回写（消费方 search 侧只累加 Redis 热度，不直写 ES）
        flushedViews.forEach((articleId, viewCount) -> {
            ContentViewCountFlushedEvent event = new ContentViewCountFlushedEvent();
            event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
            event.setBizId(articleId);
            event.setViewCount(viewCount);
            contentEventProducer.sendViewCountFlushed(event);
        });
        // 步骤6：结果留痕（有刷回才记日志，正常空轮静默）
        if (articleCount > 0) {
            log.info("浏览计数刷回完成：articles={}, totalDelta={}, events={}",
                    articleCount, totalDelta, flushedViews.size());
        }
    }

    /**
     * 从 pending 键解析 articleId（键形如 content:article:view:pending:{articleId}）。
     *
     * @param key Redis 键名
     * @return 文章ID（格式非法返回 null 并由调用方丢弃）
     */
    private Long parseArticleId(String key) {
        try {
            return Long.parseLong(
                    key.substring(ContentBizConstant.VIEW_CNT_PENDING_KEY_PREFIX.length()));
        } catch (NumberFormatException e) {
            log.warn("浏览计数键名非法（丢弃留痕）：key={}", key);
            return null;
        }
    }
}
