package com.litisle.litisleai.dto;

import com.litisle.litisleai.enums.AiUsageStatus;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户侧用量明细行（板块六 6.1：GET /ai/usage/me/details，ai_token_usage 明细的出参投影）
 *
 * <p>【口径】只返回当前登录用户自己的明细（user_id 过滤在 service 侧收口）；
 * 查询窗口默认当日、最多近 7 天（防深翻明细大查询）；不透出 prompt/正文内容字段
 * （明细表本就不存，防御性口径声明）。</p>
 *
 * @author litisle
 */
@Data
@Builder
public class AiUsageDetailItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求幂等键 */
    private String requestId;

    /** 调用序号（同一业务请求的第 N 次模型调用） */
    private Integer callSeq;

    /** 用途打标编码（AiPurposeEnum.code） */
    private String purpose;

    /** 业务场景快照（可空） */
    private String scene;

    /** 判定口径版本快照（文本 = 配置的模型标识） */
    private String modelVersion;

    /** 输入 token 数 */
    private Integer tokenIn;

    /** 输出 token 数 */
    private Integer tokenOut;

    /** 本次调用成本（元，两位小数） */
    private BigDecimal cost;

    /** 调用结果（1-SUCCESS 2-FAIL） */
    private AiUsageStatus status;

    /** 降级标记（0-正常 1-降级期产生） */
    private Integer degraded;

    /** 调用完成时间（归属日期权威源） */
    private LocalDateTime occurredAt;
}
