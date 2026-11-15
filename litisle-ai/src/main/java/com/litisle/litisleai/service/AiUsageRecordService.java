package com.litisle.litisleai.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.litisleai.domain.entity.AiPricing;
import com.litisle.litisleai.domain.entity.AiTokenUsage;
import com.litisle.litisleai.enums.AiUsageStatus;
import com.litisle.litisleai.mapper.AiPricingMapper;
import com.litisle.litisleai.mapper.AiTokenUsageMapper;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.service.support.AiUsageDetail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 用量明细记录服务（1.3 计量明细收口：幂等去重 + 成本核算 + 失败留痕）
 *
 * <p>【明细口径】幂等键 = requestId + callSeq 联合唯一（DB 唯一索引兜底），
 * 重复写入跳过不重复计费；失败调用也落一条 status=FAIL；成本 = token × ai_pricing 单价
 * （按归属日取最新生效单价，两位小数），无单价配置时成本记 0 并告警。</p>
 *
 * <p>【写入约束】ai_token_usage 只追加不更新；update-strategy=not_null 下空字段不入库，
 * 归属日期以 occurred_at 为权威源。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageRecordService {

    /** token_in/out 的指标方向标签（in/out） */
    private static final String DIRECTION_IN = "in";
    private static final String DIRECTION_OUT = "out";

    /** 成本保留两位小数（与 ai_token_usage.cost decimal(14,2) 对齐） */
    private static final int COST_SCALE = 2;

    /** 明细 Mapper（只追加写入） */
    private final AiTokenUsageMapper tokenUsageMapper;

    /** 单价配置 Mapper（成本核算） */
    private final AiPricingMapper pricingMapper;

    /** 指标打点（token 用量计数） */
    private final AiMetrics metrics;

    /**
     * 记录一次成功调用的计量明细（重复键跳过并告警，不重复计费）
     *
     * @param detail 计量上下文（成本在方法内核算）
     */
    public void recordSuccess(AiUsageDetail detail) {
        saveDetail(detail, AiUsageStatus.SUCCESS);
        // token 用量指标按值累加（ai.tokens_total{purpose,direction}）
        metrics.recordTokens(detail.getPurpose().getCode(), DIRECTION_IN, detail.getTokenIn());
        metrics.recordTokens(detail.getPurpose().getCode(), DIRECTION_OUT, detail.getTokenOut());
    }

    /**
     * 记录一次失败调用的计量明细（失败即无成本防护：token 未知按 0，成本为 0）
     *
     * @param detail 计量上下文（tokenIn/tokenOut 未知传 null）
     */
    public void recordFail(AiUsageDetail detail) {
        saveDetail(detail, AiUsageStatus.FAIL);
    }

    /**
     * 构建明细实体并写库：成本核算 → upsert 语义落库 → 重复键跳过
     */
    private void saveDetail(AiUsageDetail detail, AiUsageStatus status) {
        AiTokenUsage usage = new AiTokenUsage();
        usage.setRequestId(detail.getRequestId());
        usage.setCallSeq(detail.getCallSeq() == null ? 1 : detail.getCallSeq());
        usage.setUserId(detail.getUserId());
        usage.setPurpose(detail.getPurpose().getCode());
        usage.setScene(detail.getScene());
        usage.setModelVersion(detail.getModelVersion());
        // 文本场景 prompt_version 由 model_version 承载（口径：两者同值）
        usage.setPromptVersion(detail.getModelVersion());
        usage.setTokenIn(detail.getTokenIn() == null ? 0 : detail.getTokenIn());
        usage.setTokenOut(detail.getTokenOut() == null ? 0 : detail.getTokenOut());
        // 归属日期权威源：调用完成时间（跨天重试只计一次成功成本）
        usage.setOccurredAt(detail.getOccurredAt() == null ? LocalDateTime.now() : detail.getOccurredAt());
        usage.setStatus(status);
        usage.setDegraded(detail.getDegraded() == null ? 0 : detail.getDegraded());
        // 成本按调用结果核算：失败调用 token 已按 0 兜底，单价查询也统一走核算逻辑
        usage.setCost(calcCost(usage.getModelVersion(), usage.getOccurredAt(),
                usage.getTokenIn(), usage.getTokenOut()));
        try {
            // 幂等去重：uk(request_id, call_seq) 撞键说明同一次调用重试重复上报，跳过不重复计费
            tokenUsageMapper.insert(usage);
        } catch (DuplicateKeyException e) {
            log.warn("AI 计量明细重复写入，按幂等跳过 requestId={} callSeq={} purpose={}",
                    usage.getRequestId(), usage.getCallSeq(), usage.getPurpose());
        }
    }

    /**
     * 成本核算：token 数 × ai_pricing 单价（按归属日取最新生效单价，两位小数）
     *
     * <p>无单价配置时成本记 0 并告警（计量不断链，配置补齐后仅影响未来日期）。</p>
     */
    private BigDecimal calcCost(String modelVersion, LocalDateTime occurredAt, Integer tokenIn, Integer tokenOut) {
        LocalDate date = occurredAt.toLocalDate();
        // 取最新生效单价：effective_from ≤ 归属日中日期最大的一行（分页插件取 1 条，不拼 LIMIT 串）
        IPage<AiPricing> page = pricingMapper.selectPage(new Page<>(1, 1),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiPricing>()
                        .eq(AiPricing::getModelVersion, modelVersion)
                        .le(AiPricing::getEffectiveFrom, date)
                        .orderByDesc(AiPricing::getEffectiveFrom));
        AiPricing pricing = page.getRecords().isEmpty() ? null : page.getRecords().get(0);
        if (pricing == null) {
            // 无单价配置：成本记 0（计量不断链），告警提示补配置
            log.warn("ai_pricing 缺少单价配置 modelVersion={} date={}，本次成本按 0 落库", modelVersion, date);
            return BigDecimal.ZERO.setScale(COST_SCALE, RoundingMode.HALF_UP);
        }
        // 成本 = 输入 token × 输入单价 + 输出 token × 输出单价，两位小数（HALF_UP）
        BigDecimal inCost = pricing.getUnitPriceIn().multiply(BigDecimal.valueOf(tokenIn));
        BigDecimal outCost = pricing.getUnitPriceOut().multiply(BigDecimal.valueOf(tokenOut));
        return inCost.add(outCost).setScale(COST_SCALE, RoundingMode.HALF_UP);
    }
}
