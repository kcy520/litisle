package com.litisle.litisleai.dto;

import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 用户侧个人用量响应（板块六 6.1 / F9 收口：GET /ai/usage/me）
 *
 * <p>【口径】只能查自己的数据（userId 取自登录态，跨用户按不存在处理，与会话归属校验同口径）；
 * 花费 = token × ai_pricing 单价（与明细口径一致，两位小数）；剩余配额来自用户级配额配置，
 * 上限未定档（≤0）时剩余值用 -1 表示「不限制」。</p>
 *
 * @author litisle
 */
@Data
public class AiUsageMeResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 查询基准日期（当日） */
    private LocalDate date;

    /** 当日总花费（元，两位小数，所有用途合计） */
    private BigDecimal todayCost;

    /** 当日各用途用量与剩余配额 */
    private List<PurposeUsage> usages;

    /** 近 7 日花费统计（按日，含当日；花费统计与当日用量分离展示） */
    private List<DailyCost> recentDays;

    /**
     * 单用途当日用量 + 剩余配额
     */
    @Data
    @Builder
    public static class PurposeUsage implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 用途打标 */
        private AiPurposeEnum purpose;

        /** 当日调用次数（成功+失败；无用量为 0） */
        private Integer calls;

        /** 当日失败调用次数（无用量为 0） */
        private Integer failCalls;

        /** 当日输入 token 合计（无用量为 0） */
        private Long tokensIn;

        /** 当日输出 token 合计（无用量为 0） */
        private Long tokensOut;

        /** 当日花费（元，两位小数；无用量为 0） */
        private BigDecimal cost;

        /** 剩余调用次数（-1=未定档不限制） */
        private Long remainingCalls;

        /** 剩余 Token 总量（输入+输出合计口径；-1=未定档不限制） */
        private Long remainingTokens;
    }

    /**
     * 单日花费统计行（近 7 日列表元素）
     */
    @Data
    @Builder
    public static class DailyCost implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 统计日期 */
        private LocalDate date;

        /** 当日调用次数合计 */
        private Integer calls;

        /** 当日花费合计（元，两位小数） */
        private BigDecimal cost;
    }
}
