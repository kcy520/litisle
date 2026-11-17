package com.litisle.litisleai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litisleai.domain.entity.AiUsageDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ai_usage_daily 用量日汇总 Mapper（MyBatis-Plus）
 *
 * <p>聚合统一走 AiUsageDailyAggregateTask 的幂等覆盖写（INSERT...SELECT...ON DUPLICATE KEY UPDATE），
 * 保证预算判定当日数据可用、重复执行不翻倍；F9 用量查询（板块六 6.1）的按用途/按日聚合
 * 复用本表 GROUP BY 查询，不重复建汇总逻辑。</p>
 *
 * @author litisle
 */
@Mapper
public interface AiUsageDailyMapper extends BaseMapper<AiUsageDaily> {

    /**
     * 幂等覆盖写：按明细表聚合结果覆盖 (stat_date, user_id, purpose) 三元组的汇总行
     *
     * <p>口径：user_id 为空的明细归入系统桶（user_id=0）；calls 含成功+失败；
     * fail_calls 统计 status=#{failStatus}（调用方传 AiUsageStatus.FAIL.code，不写死数字）；
     * 重复执行按覆盖写幂等，不翻倍。</p>
     *
     * @param statDate       统计日期（聚合行归属日）
     * @param startInclusive 明细窗口起点（含）
     * @param endExclusive   明细窗口终点（不含）
     * @param failStatus     失败状态码（AiUsageStatus.FAIL.getCode()，fail_calls 统计口径）
     * @return 受影响行数（insert + update 合计）
     */
    @Insert("INSERT INTO ai_usage_daily (stat_date, user_id, purpose, calls, tokens_in, tokens_out, cost, fail_calls, degraded_calls) "
            + "SELECT #{statDate}, IFNULL(user_id, 0), purpose, COUNT(*), IFNULL(SUM(token_in), 0), IFNULL(SUM(token_out), 0), "
            + "IFNULL(SUM(cost), 0), SUM(status = #{failStatus}), SUM(degraded = 1) "
            + "FROM ai_token_usage WHERE occurred_at >= #{startInclusive} AND occurred_at < #{endExclusive} "
            + "GROUP BY IFNULL(user_id, 0), purpose "
            + "ON DUPLICATE KEY UPDATE calls = VALUES(calls), tokens_in = VALUES(tokens_in), tokens_out = VALUES(tokens_out), "
            + "cost = VALUES(cost), fail_calls = VALUES(fail_calls), degraded_calls = VALUES(degraded_calls)")
    int upsertAggregate(@Param("statDate") LocalDate statDate,
                        @Param("startInclusive") LocalDateTime startInclusive,
                        @Param("endExclusive") LocalDateTime endExclusive,
                        @Param("failStatus") int failStatus);

    /**
     * 日期区间内跨用户按用途汇总（运营侧按用途出账，6.1 口径：复用 ai_usage_daily 聚合，
     * 含系统桶 user_id=0——匿名/机审无发起用户的用量只进运营汇总）
     *
     * @param start 统计日期起点（含）
     * @param end   统计日期终点（含）
     * @return 每用途一行（calls/tokens_in/tokens_out/cost/fail_calls 为区间合计）
     */
    @Select("SELECT purpose AS purpose, SUM(calls) AS calls, SUM(fail_calls) AS failCalls, "
            + "SUM(tokens_in) AS tokensIn, SUM(tokens_out) AS tokensOut, SUM(cost) AS cost "
            + "FROM ai_usage_daily WHERE stat_date BETWEEN #{start} AND #{end} GROUP BY purpose")
    List<AiUsageSummaryRow> sumByPurposeRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * 日期区间内跨用户按日汇总（运营侧单日花费对账，6.1 口径：F9 验收
     * 「后台可按场景出账，单日花费可对账」）
     *
     * @param start 统计日期起点（含）
     * @param end   统计日期终点（含）
     * @return 每日一行（按 stat_date 升序）
     */
    @Select("SELECT stat_date AS statDate, SUM(calls) AS calls, SUM(fail_calls) AS failCalls, "
            + "SUM(tokens_in) AS tokensIn, SUM(tokens_out) AS tokensOut, SUM(cost) AS cost "
            + "FROM ai_usage_daily WHERE stat_date BETWEEN #{start} AND #{end} "
            + "GROUP BY stat_date ORDER BY stat_date")
    List<AiUsageSummaryRow> sumByDateRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * 查询指定用户的日汇总行（用户侧 F9 用量查询：只查自己的数据，跨用户按不存在处理）
     *
     * @param userId 用户ID
     * @param start  统计日期起点（含）
     * @param end    统计日期终点（含）
     * @return 该用户区间内各 (日期 × 用途) 汇总行（汇总表只有有用量才存在行）
     */
    @Select("SELECT stat_date AS statDate, purpose AS purpose, calls AS calls, fail_calls AS failCalls, "
            + "tokens_in AS tokensIn, tokens_out AS tokensOut, cost AS cost "
            + "FROM ai_usage_daily WHERE user_id = #{userId} AND stat_date BETWEEN #{start} AND #{end} "
            + "ORDER BY stat_date")
    List<AiUsageSummaryRow> listByUserRange(@Param("userId") Long userId,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);

    /**
     * 汇总查询行载体（GROUP BY 聚合结果 / 用户区间明细行的统一映射目标；
     * 未聚合字段为 null，调用方按需取用）
     */
    class AiUsageSummaryRow {

        /** 统计日期（按日聚合/用户明细行有值，按用途聚合为 null） */
        private LocalDate statDate;

        /** 用途编码（按用途聚合/用户明细行有值，按日聚合为 null） */
        private String purpose;

        /** 调用次数合计（成功+失败） */
        private Long calls;

        /** 失败调用次数合计 */
        private Long failCalls;

        /** 输入 token 合计 */
        private Long tokensIn;

        /** 输出 token 合计 */
        private Long tokensOut;

        /** 成本合计（两位小数） */
        private java.math.BigDecimal cost;

        public LocalDate getStatDate() {
            return statDate;
        }

        public void setStatDate(LocalDate statDate) {
            this.statDate = statDate;
        }

        public String getPurpose() {
            return purpose;
        }

        public void setPurpose(String purpose) {
            this.purpose = purpose;
        }

        public Long getCalls() {
            return calls;
        }

        public void setCalls(Long calls) {
            this.calls = calls;
        }

        public Long getFailCalls() {
            return failCalls;
        }

        public void setFailCalls(Long failCalls) {
            this.failCalls = failCalls;
        }

        public Long getTokensIn() {
            return tokensIn;
        }

        public void setTokensIn(Long tokensIn) {
            this.tokensIn = tokensIn;
        }

        public Long getTokensOut() {
            return tokensOut;
        }

        public void setTokensOut(Long tokensOut) {
            this.tokensOut = tokensOut;
        }

        public java.math.BigDecimal getCost() {
            return cost;
        }

        public void setCost(java.math.BigDecimal cost) {
            this.cost = cost;
        }
    }
}
