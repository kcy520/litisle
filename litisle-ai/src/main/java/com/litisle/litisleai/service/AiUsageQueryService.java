package com.litisle.litisleai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.litisleai.domain.entity.AiTokenUsage;
import com.litisle.litisleai.dto.AiUsageDetailItem;
import com.litisle.litisleai.dto.AiUsageMeResponse;
import com.litisle.litisleai.dto.AiUsageSummaryResponse;
import com.litisle.litisleai.mapper.AiTokenUsageMapper;
import com.litisle.litisleai.mapper.AiUsageDailyMapper;
import com.litisle.litisleai.mapper.AiUsageDailyMapper.AiUsageSummaryRow;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 用量查询服务（板块六 6.1 / F9 收口：用户侧个人用量 + 运营侧按用途/按日汇总出账）
 *
 * <p>【口径】数据源 ai_usage_daily（日汇总）与 ai_token_usage（明细），不重复建汇总逻辑；
 * 用户侧只能查自己的数据（userId 由 controller 自登录态传入，跨用户按不存在处理）；
 * 花费 = token × ai_pricing 单价（与明细口径一致，两位小数，直接读汇总表 cost 列）；
 * 匿名调用（user_id 空）不进用户侧查询，只进运营汇总（系统桶 user_id=0）。</p>
 *
 * <p>【防深翻】明细查询窗口默认当日、最多近 7 天；运营汇总区间默认近 7 天、上限 31 天；
 * 分页用 Page 对象 + 分页插件，不拼接 LIMIT 片段。</p>
 *
 * <p>【通俗说明】用量查询的"账房报表员"：给普通用户出个人账单（今天各用途用了多少、
 * 还剩多少额度、近 7 天花费曲线、明细流水）；给运营出总账（按用途、按日期跨用户
 * 汇总出账，匿名和机审的用量统一记在系统桶里也在这里体现）。所有数据都从已有的
 * 明细表和日汇总表读，不重复记账。报表窗口有严格限制（明细最多查 7 天、汇总最多
 * 31 天、页大小上限 100），防止有人一次翻几十万条数据把库拖垮。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageQueryService {

    /** 剩余配额哨兵值：上限未定档（≤0）时表示「不限制」 */
    private static final long REMAINING_UNLIMITED = -1L;

    /** 用户侧近 N 日花费统计窗口（天，含当日） */
    private static final int RECENT_DAYS_WINDOW = 7;

    /** 明细查询窗口上限（天，6.1 口径：默认当日或近 7 天，防深翻明细大查询） */
    private static final int DETAIL_MAX_DAYS = 7;

    /** 运营侧汇总查询区间上限（天，防大区间聚合查询） */
    private static final int SUMMARY_MAX_RANGE_DAYS = 31;

    /** 明细分页默认页大小 */
    private static final long DETAIL_DEFAULT_PAGE_SIZE = 20;

    /** 明细分页页大小上限 */
    private static final long DETAIL_MAX_PAGE_SIZE = 100;

    /** 日汇总 Mapper（按用途/按日聚合 + 用户区间行读取） */
    private final AiUsageDailyMapper usageDailyMapper;

    /** 明细 Mapper（用户明细分页读取） */
    private final AiTokenUsageMapper tokenUsageMapper;

    /** 用户级配额服务（复用用途 → 配额上限映射计算剩余配额） */
    private final AiUserQuotaService userQuotaService;

    /**
     * 用户侧个人用量查询（GET /ai/usage/me）：当日各用途用量 + 剩余配额 + 近 7 日花费统计
     *
     * @param userId 当前登录用户ID（controller 自登录态取得）
     * @return 个人用量响应（无用量用途按 0 值展示，保证配额面板完整）
     *
     * <p>【通俗说明】三段式个人账单：① 当日各用途的用量行（没用的用途补 0 值，
     * 面板显示完整）；② 每个用途附带"还剩多少额度"；③ 近 7 日每日花费（缺日的
     * 也补 0，保证图表横轴连续不掉点）。</p>
     */
    public AiUsageMeResponse usageMe(Long userId) {
        LocalDate today = LocalDate.now();
        // 当日该用户各用途汇总行（汇总表只有有用量才存在行，缺行按零值补齐展示）
        List<AiUsageSummaryRow> todayRows = usageDailyMapper.listByUserRange(userId, today, today);
        Map<AiPurposeEnum, AiUsageSummaryRow> todayByPurpose = groupByPurpose(todayRows);

        // 各用途当日用量 + 剩余配额（OTHER 仅兜底落明细不计预算，不在用户面板展示）
        List<AiUsageMeResponse.PurposeUsage> usages = new ArrayList<>();
        BigDecimal todayCost = BigDecimal.ZERO;
        for (AiPurposeEnum purpose : AiPurposeEnum.values()) {
            if (!purpose.isMetered()) {
                continue;
            }
            AiUsageSummaryRow row = todayByPurpose.get(purpose);
            long calls = row == null || row.getCalls() == null ? 0L : row.getCalls();
            long failCalls = row == null || row.getFailCalls() == null ? 0L : row.getFailCalls();
            long tokensIn = row == null || row.getTokensIn() == null ? 0L : row.getTokensIn();
            long tokensOut = row == null || row.getTokensOut() == null ? 0L : row.getTokensOut();
            BigDecimal cost = row == null || row.getCost() == null ? BigDecimal.ZERO : row.getCost();
            todayCost = todayCost.add(cost);
            usages.add(AiUsageMeResponse.PurposeUsage.builder()
                    .purpose(purpose)
                    .calls((int) calls)
                    .failCalls((int) failCalls)
                    .tokensIn(tokensIn)
                    .tokensOut(tokensOut)
                    .cost(cost)
                    .remainingCalls(remaining(calls, userQuotaService.resolveLimit(purpose).getDailyCalls()))
                    .remainingTokens(remaining(tokensIn + tokensOut, userQuotaService.resolveLimit(purpose).getDailyTokens()))
                    .build());
        }

        // 近 7 日花费统计（含当日，按日聚合自同一汇总表）
        List<AiUsageSummaryRow> recentRows = usageDailyMapper.listByUserRange(
                userId, today.minusDays(RECENT_DAYS_WINDOW - 1L), today);
        List<AiUsageMeResponse.DailyCost> recentDays = new ArrayList<>();
        Map<LocalDate, AiUsageSummaryRow> recentByDate = new LinkedHashMap<>();
        for (AiUsageSummaryRow row : recentRows) {
            recentByDate.merge(row.getStatDate(), row, (a, b) -> a);
        }
        for (int i = RECENT_DAYS_WINDOW - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            AiUsageSummaryRow row = recentByDate.get(date);
            // 缺日按零值补齐，保证前端图表横轴连续
            recentDays.add(AiUsageMeResponse.DailyCost.builder()
                    .date(date)
                    .calls(row == null || row.getCalls() == null ? 0 : row.getCalls().intValue())
                    .cost(row == null || row.getCost() == null ? BigDecimal.ZERO : row.getCost())
                    .build());
        }

        AiUsageMeResponse response = new AiUsageMeResponse();
        response.setDate(today);
        response.setTodayCost(todayCost);
        response.setUsages(usages);
        response.setRecentDays(recentDays);
        return response;
    }

    /**
     * 用户侧明细分页查询（GET /ai/usage/me/details）：只查本人明细，窗口默认当日、最多近 7 天
     *
     * @param userId  当前登录用户ID
     * @param days    查询窗口天数（1~7，默认 1=当日；越界按边界收口）
     * @param pageNo  页码（从 1 起，越界按 1 处理）
     * @param pageSize 页大小（默认 20，上限 100）
     * @return 明细分页结果（本页明细行）
     *
     * <p>【通俗说明】翻明细流水：只查本人，查询窗口和分页参数都先"收口"——窗口
     * 超过 7 天按 7 天、页大小超 100 按 100、页码从 1 起，然后按完成时间倒序
     * 分页取数。</p>
     */
    public List<AiUsageDetailItem> usageDetails(Long userId, Integer days, Integer pageNo, Integer pageSize) {
        // 窗口收口：默认当日，最多近 7 天（防深翻明细大查询）
        int windowDays = days == null || days < 1 ? 1 : Math.min(days, DETAIL_MAX_DAYS);
        // 分页参数收口：页码从 1 起，页大小上限 100
        long current = pageNo == null || pageNo < 1 ? 1 : pageNo;
        long size = pageSize == null || pageSize < 1 ? DETAIL_DEFAULT_PAGE_SIZE : Math.min(pageSize, DETAIL_MAX_PAGE_SIZE);
        LocalDate today = LocalDate.now();
        LocalDateTime windowStart = today.minusDays(windowDays - 1L).atStartOfDay();
        LocalDateTime windowEnd = today.plusDays(1).atStartOfDay();
        // 明细分页读取：user_id 等值过滤 + occurred_at 窗口，按完成时间倒序（Page 对象走分页插件）
        IPage<AiTokenUsage> page = tokenUsageMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AiTokenUsage>()
                        .eq(AiTokenUsage::getUserId, userId)
                        .ge(AiTokenUsage::getOccurredAt, windowStart)
                        .lt(AiTokenUsage::getOccurredAt, windowEnd)
                        .orderByDesc(AiTokenUsage::getOccurredAt));
        List<AiUsageDetailItem> items = new ArrayList<>();
        for (AiTokenUsage usage : page.getRecords()) {
            items.add(AiUsageDetailItem.builder()
                    .requestId(usage.getRequestId())
                    .callSeq(usage.getCallSeq())
                    .purpose(usage.getPurpose() == null ? null : usage.getPurpose().getCode())
                    .scene(usage.getScene())
                    .modelVersion(usage.getModelVersion())
                    .tokenIn(usage.getTokenIn())
                    .tokenOut(usage.getTokenOut())
                    .cost(usage.getCost())
                    .status(usage.getStatus())
                    .degraded(usage.getDegraded())
                    .occurredAt(usage.getOccurredAt())
                    .build());
        }
        return items;
    }

    /**
     * 运营侧用量汇总查询（GET /ai/usage/summary）：按用途与按日两个维度跨用户出账，
     * 复用 ai_usage_daily 聚合（含系统桶——匿名/机审无发起用户用量只进运营汇总）
     *
     * @param start 统计日期起点（含，null 默认近 7 天起点）
     * @param end   统计日期终点（含，null 默认当日；区间超上限按上限收口）
     * @return 按用途汇总 + 按日汇总
     *
     * <p>【通俗说明】运营总账：先把查询区间收口（默认近 7 天、最多 31 天、起点晚于
     * 终点自动纠正），然后出两张报表——按用途汇总（各用途共花了多少）和按日期汇总
     * （每天花了多少），含匿名/机审的系统桶数据。</p>
     */
    public AiUsageSummaryResponse summary(LocalDate start, LocalDate end) {
        // 区间收口：终点默认当日，起点默认终点前 6 天；大区间按 31 天上限截断防聚合大查询
        LocalDate safeEnd = end == null ? LocalDate.now() : end;
        LocalDate safeStart = start == null ? safeEnd.minusDays(RECENT_DAYS_WINDOW - 1L) : start;
        if (safeStart.isAfter(safeEnd)) {
            safeStart = safeEnd;
        }
        if (safeStart.isBefore(safeEnd.minusDays(SUMMARY_MAX_RANGE_DAYS - 1L))) {
            safeStart = safeEnd.minusDays(SUMMARY_MAX_RANGE_DAYS - 1L);
        }
        // 按用途聚合（跨用户求和，含系统桶 user_id=0）
        List<AiUsageSummaryResponse.PurposeSummary> byPurpose = new ArrayList<>();
        for (AiUsageSummaryRow row : usageDailyMapper.sumByPurposeRange(safeStart, safeEnd)) {
            AiPurposeEnum purpose = AiPurposeEnum.fromCode(row.getPurpose());
            byPurpose.add(AiUsageSummaryResponse.PurposeSummary.builder()
                    .purpose(purpose == null ? AiPurposeEnum.OTHER : purpose)
                    .calls(row.getCalls() == null ? 0 : row.getCalls().intValue())
                    .failCalls(row.getFailCalls() == null ? 0 : row.getFailCalls().intValue())
                    .tokensIn(row.getTokensIn() == null ? 0L : row.getTokensIn())
                    .tokensOut(row.getTokensOut() == null ? 0L : row.getTokensOut())
                    .cost(row.getCost() == null ? BigDecimal.ZERO : row.getCost())
                    .build());
        }
        // 按日聚合（单日花费对账，按日期升序）
        List<AiUsageSummaryResponse.DateSummary> byDate = new ArrayList<>();
        for (AiUsageSummaryRow row : usageDailyMapper.sumByDateRange(safeStart, safeEnd)) {
            byDate.add(AiUsageSummaryResponse.DateSummary.builder()
                    .date(row.getStatDate())
                    .calls(row.getCalls() == null ? 0 : row.getCalls().intValue())
                    .failCalls(row.getFailCalls() == null ? 0 : row.getFailCalls().intValue())
                    .tokensIn(row.getTokensIn() == null ? 0L : row.getTokensIn())
                    .tokensOut(row.getTokensOut() == null ? 0L : row.getTokensOut())
                    .cost(row.getCost() == null ? BigDecimal.ZERO : row.getCost())
                    .build());
        }
        AiUsageSummaryResponse response = new AiUsageSummaryResponse();
        response.setStart(safeStart);
        response.setEnd(safeEnd);
        response.setByPurpose(byPurpose);
        response.setByDate(byDate);
        return response;
    }

    /**
     * 汇总行按用途分组（同用途多日行合并取首行——当日窗口内每用途仅一行，merge 兜底防脏数据）
     *
     * @param rows 汇总行列表
     * @return 用途 → 汇总行
     *
     * <p>【通俗说明】把查询回来的汇总行按用途分组，同用途出现多行时取首行
     * （正常一天一用途只有一行，merge 是防脏数据的兜底）。</p>
     */
    private Map<AiPurposeEnum, AiUsageSummaryRow> groupByPurpose(List<AiUsageSummaryRow> rows) {
        Map<AiPurposeEnum, AiUsageSummaryRow> grouped = new LinkedHashMap<>();
        for (AiUsageSummaryRow row : rows) {
            AiPurposeEnum purpose = AiPurposeEnum.fromCode(row.getPurpose());
            if (purpose != null) {
                grouped.merge(purpose, row, (a, b) -> a);
            }
        }
        return grouped;
    }

    /**
     * 剩余配额计算：上限未定档（≤0）返回「不限制」哨兵值；有上限时按 已用/上限 收口不为负
     *
     * @param used  已用量
     * @param limit 上限（≤0 = 未定档）
     * @return 剩余量（-1=不限制；有上限时 max(0, limit-used)）
     *
     * <p>【通俗说明】算"还剩多少"：没设上限就返回 -1（界面显示"不限制"）；设了
     * 上限就用上限减已用，最少是 0 不出现负数。</p>
     */
    private long remaining(long used, long limit) {
        // 未定档：仅计量不拦截，展示为不限制
        if (limit <= 0) {
            return REMAINING_UNLIMITED;
        }
        return Math.max(0L, limit - used);
    }
}
