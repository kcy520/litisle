package com.litisle.litisleai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litisleai.domain.entity.AiTokenUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ai_token_usage 用量明细 Mapper（MyBatis-Plus，只追加不更新）
 *
 * <p>写入统一走 AiUsageRecordService（幂等去重 + 成本核算收口），禁止绕过直接 insert。</p>
 *
 * @author litisle
 */
@Mapper
public interface AiTokenUsageMapper extends BaseMapper<AiTokenUsage> {

    /**
     * 统计指定窗口内明细的调用次数与成本合计（聚合任务对账用）
     *
     * @param startInclusive 窗口起点（含）
     * @param endExclusive   窗口终点（不含）
     * @return calls=调用次数；cost=成本合计（无数据时 cost 为 0）
     */
    @Select("SELECT COUNT(*) AS calls, IFNULL(SUM(cost), 0) AS cost FROM ai_token_usage "
            + "WHERE occurred_at >= #{startInclusive} AND occurred_at < #{endExclusive}")
    AiDaySummary sumByWindow(@Param("startInclusive") LocalDateTime startInclusive,
                             @Param("endExclusive") LocalDateTime endExclusive);

    /**
     * 对账摘要（sumByWindow 的结果载体）
     */
    class AiDaySummary {

        /** 调用次数 */
        private Long calls;

        /** 成本合计 */
        private BigDecimal cost;

        public Long getCalls() {
            return calls;
        }

        public void setCalls(Long calls) {
            this.calls = calls;
        }

        public BigDecimal getCost() {
            return cost;
        }

        public void setCost(BigDecimal cost) {
            this.cost = cost;
        }
    }
}
