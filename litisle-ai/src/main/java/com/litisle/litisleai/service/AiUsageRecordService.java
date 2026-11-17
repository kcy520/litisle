package com.litisle.litisleai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * <p>【通俗说明】AI 的"记账先生"：每次调用模型（不管成功失败）都由它记一笔明细。
 * 记账有三条规矩——① 幂等：requestId + callSeq 是唯一键，同一笔账重报就跳过，
 * 绝不重复扣钱；② 失败也要记：失败明细 token 按 0 落库，账本永远连续；③ 成本
 * 本方法当场核算：按调用当天的最新生效单价（输入价 × 输入 token + 输出价 × 输出
 * token）算出金额，保留两位小数；没配单价就记 0 并告警，账不能断。明细表只追加
 * 不改旧账。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageRecordService {

    /** 成本保留两位小数（与 ai_token_usage.cost decimal(14,2) 对齐） */
    private static final int COST_SCALE = 2;

    /** 明细 Mapper（只追加写入） */
    private final AiTokenUsageMapper tokenUsageMapper;

    /** 单价配置 Mapper（成本核算） */
    private final AiPricingMapper pricingMapper;

    /** 指标打点（token 用量计数） */
    private final AiMetrics metrics;

    /** 预算服务（明细落库成功后实扣 Redis 预算计数，预算判定与账本口径分离） */
    private final AiBudgetService budgetService;

    /**
     * 记录一次成功调用的计量明细（重复键跳过并告警，不重复计费）
     *
     * @param detail 计量上下文（成本在方法内核算）
     *
     * <p>【通俗说明】记一笔成功账：明细落库（状态 = 成功），并把输入/输出 token
     * 累加进监控指标。</p>
     */
    public void recordSuccess(AiUsageDetail detail) {
        saveDetail(detail, AiUsageStatus.SUCCESS);
        // token 用量指标按值累加（ai.tokens_total{purpose,direction}）
        metrics.recordTokens(detail.getPurpose().getCode(), AiMetrics.DIRECTION_IN, detail.getTokenIn());
        metrics.recordTokens(detail.getPurpose().getCode(), AiMetrics.DIRECTION_OUT, detail.getTokenOut());
    }

    /**
     * 记录一次失败调用的计量明细（失败即无成本防护：token 未知按 0，成本为 0）
     *
     * @param detail 计量上下文（tokenIn/tokenOut 未知传 null）
     *
     * <p>【通俗说明】记一笔失败账：状态 = 失败，token 未知按 0，成本算出来自然为 0。</p>
     */
    public void recordFail(AiUsageDetail detail) {
        saveDetail(detail, AiUsageStatus.FAIL);
    }

    /**
     * 构建明细实体并写库：成本核算 → upsert 语义落库 → 重复键跳过 → 预算计数实扣
     *
     * <p>【通俗说明】记账的实际动作：把计量上下文填进明细实体（空 token 补 0、
     * 归属时间以调用完成时间为准）、按模型和归属日算成本，然后插入明细表——
     * 撞唯一键说明这笔账记过了，跳过并告警，不重复计费。落库成功后同步累加
     * Redis 预算计数（失败不影响记账）。</p>
     */
    private void saveDetail(AiUsageDetail detail, AiUsageStatus status) {
        AiTokenUsage usage = new AiTokenUsage();
        usage.setRequestId(detail.getRequestId());
        usage.setCallSeq(detail.getCallSeq() == null ? 1 : detail.getCallSeq());
        usage.setUserId(detail.getUserId());
        usage.setPurpose(detail.getPurpose());
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
            // 预算计数实扣：仅成功且有成本的明细累加 Redis 日计数（重复写入已跳过不扣；
            // 失败明细成本为 0 自然不扣；Redis 异常在 recordCost 内吞掉，不破坏计量主链路）
            if (status == AiUsageStatus.SUCCESS) {
                budgetService.recordCost(usage.getPurpose(), usage.getCost(),
                        usage.getOccurredAt().toLocalDate());
            }
        } catch (DuplicateKeyException e) {
            log.warn("AI 计量明细重复写入，按幂等跳过 requestId={} callSeq={} purpose={}",
                    usage.getRequestId(), usage.getCallSeq(), usage.getPurpose());
        }
    }

    /**
     * 成本核算：token 数 × ai_pricing 单价（按归属日取最新生效单价，两位小数）
     *
     * <p>无单价配置时成本记 0 并告警（计量不断链，配置补齐后仅影响未来日期）。</p>
     *
     * <p>【通俗说明】算钱公式：找"生效日期 ≤ 调用日"里最新的一条单价配置，
     * 成本 = 输入 token × 输入单价 + 输出 token × 输出单价，四舍五入到两位小数；
     * 查不到单价就记 0 并告警（等配置补上后从下一个调用开始正常算）。</p>
     */
    private BigDecimal calcCost(String modelVersion, LocalDateTime occurredAt, Integer tokenIn, Integer tokenOut) {
        LocalDate date = occurredAt.toLocalDate();
        // 取最新生效单价：effective_from ≤ 归属日中日期最大的一行（分页插件取 1 条，不拼 LIMIT 串）
        IPage<AiPricing> page = pricingMapper.selectPage(new Page<>(1, 1),
                new LambdaQueryWrapper<AiPricing>()
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
