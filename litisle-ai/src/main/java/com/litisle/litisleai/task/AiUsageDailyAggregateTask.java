package com.litisle.litisleai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litisleai.domain.entity.AiUsageDaily;
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
 * <p>【口径】归属日期按明细 occurred_at（跨天重试只计一次成功成本）；覆盖写保证预算判定
 * 当日数据可用、重复执行不翻倍；聚合完成后做明细与汇总差异核对（>1% 记日志告警）。</p>
 *
 * <p>【调度】每小时整点聚合 + 启动 30 秒后补跑一次（fixedDelay 设为极大值使固定期只触发一次）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiUsageDailyAggregateTask {

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
        int affected = usageDailyMapper.upsertAggregate(statDate, start, end);
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
