package com.litisle.litisleai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litisleai.domain.entity.AiUsageDaily;
import com.litisle.litisleai.enums.AiUsageStatus;
import com.litisle.litisleai.mapper.AiTokenUsageMapper;
import com.litisle.litisleai.mapper.AiUsageDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 用量日汇总聚合任务（1.3 聚合口径：每小时 + 启动延迟一次，对当日与前一日幂等覆盖写）
 *
 * <p>【职责】扫描 ai_token_usage 明细表（每次 AI 调用的 token/成本流水），按 occurred_at
 * 时间窗口聚合后覆盖写入 ai_usage_daily 汇总表；聚合 SQL 位于 upsertAggregate()
 * （按用户/用途维度分组统计），本类只负责调度与对账。</p>
 *
 * <p>【为什么需要汇总表】明细表数据量大，而预算判定（用户当日用量是否超额度）需要
 * 频繁查当日成本，直接扫明细太慢；聚合后查汇总表即可，查询成本与明细量无关。</p>
 *
 * <p>【为什么聚两天】①当日：保证预算判定实时性（最新用量最多滞后 1 小时）；
 * ②前一日：补齐跨天临界数据——23:59 发起的调用明细落库时 occurred_at 可能已到次日，
 * 前一日窗口会漏，整点再聚一次兜底。</p>
 *
 * <p>【口径与幂等】归属日期按明细 occurred_at 判定（跨天重试只计一次成功成本）；
 * 覆盖写保证同一日期重复执行不翻倍，任务失败后下轮整点重跑覆盖写即自愈。</p>
 *
 * <p>【对账】每次聚合后核对明细成本合计 vs 汇总成本合计，差异 >1% 记 warn 告警，
 * 用于发现聚合 SQL 异常或数据丢失导致的计费不准，需人工排查计量链路。</p>
 *
 * <p>【调度】每小时整点聚合 + 启动 30 秒后补跑一次（fixedDelay 设为极大值使固定期只触发一次）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiUsageDailyAggregateTask {

//     把 AI 调用的成本明细流水聚合成每日汇总表，供预算判定使用，并自带对账告警。
// ### 核心做三件事
// 1. 聚合写入 每小时扫描`ai_token_usage` 明细表（每次 AI 调用的 token/成本流水），按`occurred_at` 时间窗口（[00:00, 次日 00:00)）聚合，覆盖写入`ai_usage_daily` 汇总表。聚合 SQL 在`usageDailyMapper.upsertAggregate()` ，按用户/用途维度分组。
// 2. 每次聚两天
//    - 当日 ：保证预算判定（用户当日用量是否超额度）的数据实时性，最多滞后 1 小时；
//    - 前一日 ：兜底补齐跨天临界数据（如 23:59 的调用明细落库时`occurred_at` 已到次日，前一日窗口会漏）。
// 3. 对账告警 （ checkDiff ）
//    每次聚合后核对“明细成本合计 vs 汇总成本合计”，差异 >1% 记 warn 日志，用于发现聚合 SQL 异常或数据丢失导致的计费不准。


// ### 扫描什么？
// 扫描`ai_token_usage` 明细表 （每次 AI 调用的 token/成本流水），按`occurred_at` 时间窗口聚合， 覆盖写 入`ai_usage_daily` 汇总表 。聚合逻辑本身在`usageDailyMapper.upsertAggregate()` 的 SQL 里（按用户/用途维度分组统计）。

// ### 业务逻辑链路
// 1. 为什么要汇总表？ 明细表数据量大，而“预算判定”（比如用户当日用量是否超额度）需要频繁查当日成本——直接扫明细表太慢。每小时把明细聚合成日汇总，预算判定查汇总表即可，O(1) 级别。
// 2. 为什么每次聚“两天”（当日 + 前一日）？
//    - 聚 当日 ：保证预算判定的数据实时性（最新用量最多滞后 1 小时）。
//    - 聚 前一日 ：补齐跨天临界数据——比如 23:59 发起的调用，明细落库时`occurred_at` 可能已到次日，前一日窗口可能漏数据，再跑一次兜底。
// 3. 幂等覆盖写 ：同一日期重复执行不翻倍（upsert 语义），所以任务失败后下轮整点重跑就能自愈。
// 4. 对账机制 （ checkDiff ）：每次聚合后核对“明细成本合计 vs 汇总成本合计”，差异 >1% 记 warn 日志——这是计量链路的一致性哨兵，防止聚合 SQL 有 bug 或数据丢失导致计费不准。
// 5. 调度 ：每小时整点 + 启动 30 秒补跑一次（同样的一次性触发技巧）。


// 理论上`ai_usage_daily` 就是`ai_token_usage` 按 [00:00, 次日 00:00) 窗口 + 用户/用途维度聚合的结果，两边应该完全相等。但 对账机制存在的意义就是：聚合不是原子的、数据是活的 ，差异会从以下几个环节渗入：
// ### 1. 聚合与对账之间存在时间差（当日窗口的主要差异来源）
// 整点 00:00  →  upsert 聚合（读取窗口快照）  →  checkDiff（重新读明细）
// `upsertAggregate` 执行期间和之后， 新明细还在持续落库 （用户正在调用 AI）。聚合读的是“那一刻”的窗口数据，checkDiff 读的是“几毫秒后”的明细——当日窗口的明细合计天然会略大于汇总合计。所以 >1% 阈值主要防的是“大差异”，容忍这种在途写入的小抖动 。

// ### 2. 覆盖写不清“多余的行”（汇总偏大的经典来源）
// upsert 的语义是“ 有明细的组合 → 更新/插入 ”，但反过来不成立：
// - 某用户昨天聚合产生了汇总行，之后 明细被删除/修正 （人工修数、数据治理任务）
// - 下一轮聚合时该组合在窗口内 无明细 → upsert 既不生成新行，也 不会删掉旧行
// - 结果：汇总表残留一行“幽灵数据”，汇总合计 > 明细合计
// 这是覆盖写（upsert）天然的盲区——它只做加法和改，不做减法。

// ### 3. 窗口边界与时区口径错位
// - 明细的`occurred_at` 归属日和汇总的`stat_date` 必须用 同一个时区 判定。如果数据库连接时区和应用`LocalDate.now()` 时区不一致，23:xx~00:xx 之间的明细会被划到不同的“日”，窗口合计就对不上。
// - 迟到落库的明细：调用发生在 23:59，明细异步写入时`occurred_at` 已是次日 00:00，归属日漂移。
// ### 4. 聚合执行失败或部分成功
// - 上轮整点聚合抛异常（`log.error` 后跳过），期间没有任何重试——如果下一轮又只聚了当日成功、前一日失败，前一日汇总就是 陈旧值 。
// - 多实例部署时若没有分布式锁，两个节点同时 upsert，虽然幂等覆盖写保证不翻倍，但并发交错下可能读到中间态。
// ### 5. 精度与舍入差异
// - SQL 侧`SUM(cost)` 的 DECIMAL 精度/舍入规则 vs Java 侧 BigDecimal 逐行累加（ checkDiff 里的循环求和），两边舍入路径不同，理论上会产生极小偏差——这类差异远低于 1%，不会触发告警。

    /** 明细与汇总成本差异告警阈值（>1% 告警） */
    private static final BigDecimal DIFF_ALERT_RATIO = new BigDecimal("0.01");

    /** 成本比较精度（按两位小数比较后计算差异比例） */
    private static final int COST_SCALE = 2;

    /** 明细 Mapper（窗口对账读） */
    private final AiTokenUsageMapper tokenUsageMapper;

    /** 汇总 Mapper（覆盖写 + 差异核对读） */
    private final AiUsageDailyMapper usageDailyMapper;

    /**
     * 定时聚合入口：每小时整点 + 启动延迟一次（@Scheduled 可重复标注）
     */
    @Scheduled(cron = "0 0 * * * ?")
    @Scheduled(initialDelay = 30_000L, fixedDelay = Long.MAX_VALUE)
    public void aggregate() {
        try {
            // 对当日与前一日各做一次幂等覆盖写（保证预算判定当日数据可用 + 补齐跨天临界数据）
            LocalDate today = LocalDate.now();
            upsertForDay(today);
            upsertForDay(today.minusDays(1));
            // 差异核对：明细合计 vs 汇总合计，>1% 记日志告警（人工排查计量链路）
            checkDiff(today);
            checkDiff(today.minusDays(1));
        } catch (Exception e) {
            // 聚合失败不中断服务（下轮整点重试覆盖写自愈），仅记录错误
            log.error("AI 用量日汇总聚合失败", e);
        }
    }

    /**
     * 单日聚合：按明细窗口（[当日 00:00, 次日 00:00)）聚合覆盖写汇总表
     */
    private void upsertForDay(LocalDate statDate) {
        LocalDateTime start = statDate.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        int affected = usageDailyMapper.upsertAggregate(statDate, start, end, AiUsageStatus.FAIL.getCode());
        log.info("AI 用量日汇总聚合完成 statDate={} affectedRows={}", statDate, affected);
    }

    /**
     * 差异核对：明细成本合计 vs 汇总成本合计，差异比例 >1% 告警
     */
    private void checkDiff(LocalDate statDate) {
        LocalDateTime start = statDate.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        // 明细侧合计（occurred_at 窗口）
        AiTokenUsageMapper.AiDaySummary detailSum =
                tokenUsageMapper.sumByWindow(start, end);
        // 汇总侧合计（stat_date 精确日，跨用户/用途求和）
        List<AiUsageDaily> rows = usageDailyMapper.selectList(new LambdaQueryWrapper<AiUsageDaily>()
                .eq(AiUsageDaily::getStatDate, statDate));
        BigDecimal dailyCost = BigDecimal.ZERO;
        for (AiUsageDaily row : rows) {
            dailyCost = dailyCost.add(row.getCost() == null ? BigDecimal.ZERO : row.getCost());
        }
        BigDecimal detailCost = detailSum.getCost() == null ? BigDecimal.ZERO : detailSum.getCost();
        // 明细无数据时跳过核对（空窗口差异无意义）
        if (detailCost.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        BigDecimal diffRatio = detailCost.subtract(dailyCost).abs()
                .divide(detailCost, 4, RoundingMode.HALF_UP);
        if (diffRatio.compareTo(DIFF_ALERT_RATIO) > 0) {
            log.warn("AI 计量明细与汇总差异超阈值（>1%）statDate={} detailCost={} dailyCost={} diffRatio={}",
                    statDate, detailCost.setScale(COST_SCALE, RoundingMode.HALF_UP), dailyCost, diffRatio);
        }
    }
}
