package com.litisle.litisleai.service.support;

import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次模型调用的计量上下文（AiUsageRecordService 写明细的统一入参）
 *
 * <p>成本不入参：由 AiUsageRecordService 按 model_version + 归属日从 ai_pricing 核算。</p>
 */
@Data
@Builder
public class AiUsageDetail {

    /** 请求幂等键（业务侧生成，重试复用同一键） */
    private String requestId;

    /** 调用序号（同一业务请求的第 N 次模型调用；机审切片通道固定 1，Agent 多轮按轮次递增） */
    private Integer callSeq;

    /** 发起用户ID（机审无发起用户为 null） */
    private Long userId;

    /** 用途打标 */
    private AiPurposeEnum purpose;

    /** 业务场景快照（可空） */
    private String scene;

    /** 模型版本（文本 = 配置的模型标识，prompt_version 同值） */
    private String modelVersion;

    /** 输入 token 数（未知传 null，按 0 落库） */
    private Integer tokenIn;

    /** 输出 token 数（未知传 null，按 0 落库） */
    private Integer tokenOut;

    /** 降级标记（0-正常 1-降级期产生） */
    private Integer degraded;

    /** 调用完成时间（归属日期权威源） */
    private LocalDateTime occurredAt;
}
