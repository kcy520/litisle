package com.litisle.litisleai.service.support;

import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次模型调用的计量上下文（AiUsageRecordService 写明细的统一入参）
 *
 * <p>成本不入参：由 AiUsageRecordService 按 model_version + 归属日从 ai_pricing 核算。</p>
 *
 * <p>【通俗说明】这是 AI 每次调用模型时的"话费账单条目"：谁调的（userId）、调来干什么
 * （purpose/scene）、用的哪个模型（modelVersion）、吃了多少 token（tokenIn/tokenOut）、
 * 是不是降级期间产生的（degraded）、什么时候调完的（occurredAt）。注意成本（钱）不在这
 * 张单子上——记录服务拿到账单条目后，再按模型版本和归属日期去 ai_pricing 表换算成
 * 实际费用。requestId + callSeq 用来定位"哪个业务请求的第几次调用"，重试时复用同一个
 * requestId，保证重复记账可识别。</p>
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
