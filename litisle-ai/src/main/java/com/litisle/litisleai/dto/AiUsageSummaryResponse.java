package com.litisle.litisleai.dto;

import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 运营侧用量汇总响应（板块六 6.1 / F9 验收「后台可按场景出账，单日花费可对账」：
 * GET /ai/usage/summary）
 *
 * <p>【口径】跨用户求和（含系统桶 user_id=0——匿名/机审无发起用户的用量只进运营汇总，
 * 不进用户侧查询）；按用途/按日聚合复用 ai_usage_daily，不重复建汇总逻辑；
 * 查询区间默认近 7 天，上限外置防深翻大查询。</p>
 *
 * @author litisle
 */
@Data
public class AiUsageSummaryResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 统计区间起点（含） */
    private LocalDate start;

    /** 统计区间终点（含） */
    private LocalDate end;

    /** 按用途汇总（每用途一行区间合计） */
    private List<PurposeSummary> byPurpose;

    /** 按日汇总（每日一行，按日期升序） */
    private List<DateSummary> byDate;

    /**
     * 单用途区间汇总行
     */
    @Data
    @Builder
    public static class PurposeSummary implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 用途打标 */
        private AiPurposeEnum purpose;

        /** 区间调用次数合计（成功+失败） */
        private Integer calls;

        /** 区间失败调用次数合计 */
        private Integer failCalls;

        /** 区间输入 token 合计 */
        private Long tokensIn;

        /** 区间输出 token 合计 */
        private Long tokensOut;

        /** 区间花费合计（元，两位小数） */
        private BigDecimal cost;
    }

    /**
     * 单日汇总行（单日花费对账）
     */
    @Data
    @Builder
    public static class DateSummary implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 统计日期 */
        private LocalDate date;

        /** 当日调用次数合计 */
        private Integer calls;

        /** 当日失败调用次数合计 */
        private Integer failCalls;

        /** 当日输入 token 合计 */
        private Long tokensIn;

        /** 当日输出 token 合计 */
        private Long tokensOut;

        /** 当日花费合计（元，两位小数） */
        private BigDecimal cost;
    }
}
