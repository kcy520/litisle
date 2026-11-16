package com.litisle.litisleai.service;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.provider.text.TextChatRequest;
import com.litisle.litisleai.provider.text.TextChatResult;
import com.litisle.litisleai.provider.text.TextModelProvider;
import com.litisle.litisleai.service.support.AiUsageDetail;
import com.litisle.litisleapi.dto.ai.AiChatRequestDTO;
import com.litisle.litisleapi.dto.ai.AiChatResponseDTO;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 文本对话服务（1.4 内部 Feign 端点的业务编排：契约校验 → 预算判定 → 通道调用 → 计量留痕）
 *
 * <p>【编排步骤】① purpose/requestId 必传校验（缺省拒绝并告警）→ ② OTHER「未归类调用」告警 →
 * ③ 预算桶判定（生成类 EXCEEDED 拒绝；机审桶 EXCEEDED 不停调用、告警后继续）→
 * ④ 调用 provider（fail-fast）→ ⑤ 成功/失败均落计量明细 → ⑥ 组装响应。</p>
 *
 * <p>【callSeq 口径】本通道为无状态单次调用（机审切片 requestId=taskId:chunkIndex 已按调用单位唯一），
 * callSeq 固定 1；Agent 多轮（板块二）在 service 层直接调 provider 并按轮次传 callSeq，不经本端点。</p>
 *
 * <p>【计量承诺】调用失败也落一条 status=FAIL 明细（失败即无成本防护）；
 * 同一 requestId+callSeq 重试复用不重复计费（DB 唯一键兜底）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    /** 计量明细 callSeq 固定值（无状态单次调用通道） */
    private static final int CHANNEL_CALL_SEQ = 1;

    /** 文本模型通道端口 */
    private final TextModelProvider textModelProvider;

    /** 通道配置（provider 指标标签来源） */
    private final AiProviderProperties providerProperties;

    /** 计量明细服务 */
    private final AiUsageRecordService usageRecordService;

    /** 预算分账判定 */
    private final AiBudgetService budgetService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** 机审桶用途（EXCEEDED 不停调用，转全量人工 + 告警）：编码取枚举，禁止手写字符串 */
    private static final List<String> MODERATION_PURPOSES = List.of(
            AiPurposeEnum.MODERATION_TEXT.getCode(), AiPurposeEnum.MODERATION_IMAGE.getCode());

    /**
     * 处理一次内部对话请求：契约校验 → 预算判定 → 模型调用 → 计量留痕 → 组装响应
     *
     * @param dto AiClient 契约入参（purpose/requestId 必传）
     * @return 模型原始文本输出 + 模型标识 + 合规凭证 ID
     * @throws BizException purpose/requestId 缺失（80001/80002）、生成类预算超限（80004）、
     *                      模型通道未启用或调用失败（80005）
     */
    public AiChatResponseDTO chat(AiChatRequestDTO dto) {
        // ① purpose 必传校验：缺省/未知值拒绝并告警，防「忘传」被默认归机审
        if (dto == null || dto.getPurpose() == null) {
            log.warn("AI 调用缺省 purpose，端点侧拒绝 requestId={}", dto == null ? null : dto.getRequestId());
            throw new BizException(ErrorCode.AI_PURPOSE_REQUIRED);
        }
        // ② requestId 必传校验：计量幂等键的必要组成
        if (dto.getRequestId() == null || dto.getRequestId().isBlank()) {
            log.warn("AI 调用缺省 requestId，端点侧拒绝 purpose={}", dto.getPurpose().getCode());
            throw new BizException(ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        // OTHER 兜底用途出现即按「未归类调用」告警观察（仍放行落明细）
        if (!dto.getPurpose().isMetered()) {
            log.warn("AI 未归类调用（OTHER）requestId={} scene={}", dto.getRequestId(), dto.getScene());
        }
        // ③ 预算桶判定（判定读汇总表 + 60 秒本地缓存）
        AiBudgetDecision decision = budgetService.check(dto.getPurpose());
        if (decision == AiBudgetDecision.EXCEEDED) {
            enforceBudget(dto.getPurpose(), dto.getRequestId());
        } else if (decision == AiBudgetDecision.WARN) {
            // ≥80%：站内提醒口径，只提醒不停用
            log.warn("AI 预算预警（≥80%）purpose={} requestId={}", dto.getPurpose().getCode(), dto.getRequestId());
        }

        // ④ 调用模型通道（fail-fast）：耗时起点用于 Timer 与日志
        long start = System.currentTimeMillis();
        try {
            TextChatResult result = textModelProvider.chat(TextChatRequest.builder()
                    .systemPrompt(dto.getSystemPrompt())
                    .userPrompt(dto.getUserPrompt())
                    .temperature(dto.getTemperature())
                    .build());
            long costMs = System.currentTimeMillis() - start;
            // ⑤ 成功留痕：callSeq 固定 1（无状态单次调用通道），归属时间 = 调用完成时间
            usageRecordService.recordSuccess(AiUsageDetail.builder()
                    .requestId(dto.getRequestId())
                    .callSeq(CHANNEL_CALL_SEQ)
                    .userId(null)
                    .purpose(dto.getPurpose())
                    .scene(dto.getScene())
                    .modelVersion(result.getModelVersion())
                    .tokenIn(result.getTokenIn())
                    .tokenOut(result.getTokenOut())
                    .occurredAt(LocalDateTime.now())
                    .build());
            metrics.recordCall(dto.getPurpose().getCode(), providerProperties.getProviderName(),
                    AiMetrics.RESULT_SUCCESS);
            metrics.recordLatency(dto.getPurpose().getCode(), costMs);
            // ⑥ 组装响应（模型原始文本，结构校验由调用方负责）
            AiChatResponseDTO resp = new AiChatResponseDTO();
            resp.setContent(result.getContent());
            resp.setModel(result.getModelVersion());
            resp.setProviderTraceId(result.getProviderTraceId());
            return resp;
        } catch (BizException e) {
            // 通道未启用/调用失败：失败也留明细（失败即无成本防护），异常继续上抛（fail-fast 不吞错）
            usageRecordService.recordFail(AiUsageDetail.builder()
                    .requestId(dto.getRequestId())
                    .callSeq(CHANNEL_CALL_SEQ)
                    .userId(null)
                    .purpose(dto.getPurpose())
                    .scene(dto.getScene())
                    .modelVersion(providerProperties.getModel())
                    .tokenIn(null)
                    .tokenOut(null)
                    .occurredAt(LocalDateTime.now())
                    .build());
            metrics.recordCall(dto.getPurpose().getCode(), providerProperties.getProviderName(),
                    AiMetrics.RESULT_FAIL);
            metrics.recordLatency(dto.getPurpose().getCode(), System.currentTimeMillis() - start);
            throw e;
        }
    }

    /**
     * 预算超限执行语义：生成类停用返回明确错误码；机审桶不停调用、告警后继续
     * （机审额度不因超支而静默停摆——转全量人工 + 告警由 review 侧链路承担）
     */
    private void enforceBudget(AiPurposeEnum purpose, String requestId) {
        if (MODERATION_PURPOSES.contains(purpose.getCode())) {
            // 机审桶 100%：不停调用，告警观察（AiBudgetService 每分钟缓存判定，告警日志自然节流）
            log.warn("机审桶预算已达 100%，不停调用转告警 purpose={} requestId={}", purpose.getCode(), requestId);
            return;
        }
        // 生成类 100%：只停该用途新请求，返回明确错误码，不静默返回空结果
        log.warn("生成类用途预算已达 100%，停新请求 purpose={} requestId={}", purpose.getCode(), requestId);
        metrics.recordQuotaExceeded(purpose.getCode());
        throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED);
    }
}
