package com.litisle.litisleai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litisleai.domain.entity.AiUsageDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ai_usage_daily 用量日汇总 Mapper（MyBatis-Plus）
 *
 * <p>聚合统一走 AiUsageDailyAggregateTask 的幂等覆盖写（INSERT...SELECT...ON DUPLICATE KEY UPDATE），
 * 保证预算判定当日数据可用、重复执行不翻倍。</p>
 *
 * @author litisle
 */
@Mapper
public interface AiUsageDailyMapper extends BaseMapper<AiUsageDaily> {

    /**
     * 幂等覆盖写：按明细表聚合结果覆盖 (stat_date, user_id, purpose) 三元组的汇总行
     *
     * <p>口径：user_id 为空的明细归入系统桶（user_id=0）；calls 含成功+失败；
     * fail_calls 统计 status=2；重复执行按覆盖写幂等，不翻倍。</p>
     *
     * @param statDate       统计日期（聚合行归属日）
     * @param startInclusive 明细窗口起点（含）
     * @param endExclusive   明细窗口终点（不含）
     * @return 受影响行数（insert + update 合计）
     */
    @Insert("INSERT INTO ai_usage_daily (stat_date, user_id, purpose, calls, tokens_in, tokens_out, cost, fail_calls, degraded_calls) "
            + "SELECT #{statDate}, IFNULL(user_id, 0), purpose, COUNT(*), IFNULL(SUM(token_in), 0), IFNULL(SUM(token_out), 0), "
            + "IFNULL(SUM(cost), 0), SUM(status = 2), SUM(degraded = 1) "
            + "FROM ai_token_usage WHERE occurred_at >= #{startInclusive} AND occurred_at < #{endExclusive} "
            + "GROUP BY IFNULL(user_id, 0), purpose "
            + "ON DUPLICATE KEY UPDATE calls = VALUES(calls), tokens_in = VALUES(tokens_in), tokens_out = VALUES(tokens_out), "
            + "cost = VALUES(cost), fail_calls = VALUES(fail_calls), degraded_calls = VALUES(degraded_calls)")
    int upsertAggregate(@Param("statDate") LocalDate statDate,
                        @Param("startInclusive") LocalDateTime startInclusive,
                        @Param("endExclusive") LocalDateTime endExclusive);
}
