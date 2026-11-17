package com.litisle.litisleai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.provider.text.TextChatRequest;
import com.litisle.litisleai.provider.text.TextChatResult;
import com.litisle.litisleai.provider.text.TextModelProvider;
import com.litisle.litisleai.service.support.AiUsageDetail;
import com.litisle.litisleapi.config.AiLabelDimensionsProperties;
import com.litisle.litisleapi.config.AiLabelDimensionsProperties.Dimension;
import com.litisle.litisleapi.dto.ai.AiLabelRequestDTO;
import com.litisle.litisleapi.dto.ai.AiLabelResponseDTO;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 索引期打标服务（板块八 1.3：ai_labels 维度标注固定管道，供 search 索引链路 Feign 调用）
 *
 * <p>【职责边界】本类收口打标链路：参数校验 → 预算判定 → 注册表动态拼装 prompt → 1 次 LLM
 * 输出全维度标签 JSON → service 侧按注册表词表校验（回喂重试复用 structuredRetryBudget）→
 * 返回标签集；计量与预算判定在本类，重试语义由 search 消费侧承担。</p>
 *
 * <p>【失败语义】fail-fast：参数缺失 / 预算超限 / 重试耗尽均抛 BizException 上抛，
 * search 侧打标失败不阻断索引主链路（ai_labels 留空，该维度过滤退化为语义兜底）。
 * prompt 由维度注册表动态拼装：仅生成 filter 词表型 + semantic 型维度（range/param 型不可标注），
 * 新增维度改注册表配置零代码。</p>
 *
 * <p>【计量口径】purpose=LABELING（索引期管线独立预算桶，不开放用户端点）；
 * 打标无发起用户，userId 为空；重试轮次按 callSeq 递增落明细。</p>
 *
 * <p>【通俗说明】文章进搜索索引时，AI 会给文章贴"属性标签"（风格、难度之类），
 * 方便后续按属性筛选内容。本类就是打标流水线：读一份"维度注册表"配置决定要打哪些
 * 标签（哪些是选择题——只能从固定词表里选，哪些是填空题——AI 自由发挥），按配置
 * 动态拼 prompt 发给模型，再逐项检查 AI 的答案——选择题选了词表以外的选项就退回
 * 重做（把具体错误喂回给模型让它自己改），没把握的维度允许留空不硬造。整个过程
 * 单独记一笔 LABELING 预算账，重试耗尽就报错交给上游处理（不阻断文章入库）。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiLabelService {

    /** 打标场景快照（计量明细 scene 留痕用） */
    private static final String LABEL_SCENE = "index-labeling";

    /** 文本模型通道（provider.text.enabled=false 时调用抛明确异常转 fail-fast） */
    private final TextModelProvider textModelProvider;

    /** 计量明细服务 */
    private final AiUsageRecordService usageRecordService;

    /** 预算分账判定（LABELING 独立桶） */
    private final AiBudgetService budgetService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** 通道配置（model 版本快照 / provider 标签） */
    private final AiProviderProperties providerProperties;

    /** Agent 配置（结构化重试预算 structuredRetryBudget 复用） */
    private final AiAgentProperties agentProperties;

    /** 维度注册表（打标 prompt 动态拼装 + 词表校验唯一数据源） */
    private final AiLabelDimensionsProperties dimensionsProperties;

    /** JSON 解析器 */
    private final ObjectMapper objectMapper;

    /**
     * 索引期打标主流程（POST /api/feign/ai/label 编排入口）
     *
     * <p>【业务逻辑步骤】
     * 1. 参数校验（requestId/title/content 必传，fail-fast）；
     * 2. 注册表可用性检查（enabled=false 或无可标注维度 → 返回空标签集）；
     * 3. 预算判定（LABELING 桶 EXCEEDED → fail-fast 上抛，重试语义由 search 侧承担）；
     * 4. 注册表动态拼装打标 prompt（filter 词表型 + semantic 型维度）；
     * 5. 1 次 LLM 输出全维度标签 JSON，service 侧按注册表校验（越表回喂重试 1 次）；
     * 6. 校验通过返回标签集（低置信维度缺失，不硬造）。</p>
     *
     * @param dto 打标请求（requestId/title/content 必传）
     * @return ai_labels 维度标签集（无置信标签时为空映射）
     * @throws BizException 参数缺失 / 预算超限 / 模型通道失败且重试耗尽 / 输出校验终态不过
     *
     * <p>【通俗说明】主流程六步：① 验参数必传；② 注册表关着或没有可打维度就直接
     * 返回空标签；③ 查 LABELING 预算桶，超限报错；④ 按注册表拼 prompt；⑤ 进入
     * "调模型 → 校验 → 不过就把错误回喂重试"的循环（有重试次数上限，每一轮不管
     * 成败都记账）；⑥ 重试用完还不过就报错收场。</p>
     */
    public AiLabelResponseDTO label(AiLabelRequestDTO dto) {
        // ① 参数校验（requestId 是计量幂等键组成，title/content 是打标依据，均必传）
        if (dto == null || dto.getRequestId() == null || dto.getRequestId().isBlank()) {
            throw new BizException(ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()
                || dto.getContent() == null || dto.getContent().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "打标请求 title/content 必传");
        }
        // ② 注册表可用性：enabled=false 或无可标注维度（filter+semantic）→ 直接返回空标签集
        Map<String, Dimension> labelableDims = resolveLabelableDimensions();
        if (!dimensionsProperties.isEnabled() || labelableDims.isEmpty()) {
            log.info("维度注册表不可用或无可标注维度，返回空标签集 requestId={}", dto.getRequestId());
            return emptyResponse();
        }
        // ③ 预算判定：LABELING 桶超限 fail-fast（索引链路消费侧承担重试与降级）
        if (budgetService.check(AiPurposeEnum.LABELING) == AiBudgetDecision.EXCEEDED) {
            log.warn("索引期打标预算超限，拒绝本次打标 requestId={}", dto.getRequestId());
            metrics.recordQuotaExceeded(AiPurposeEnum.LABELING.getCode());
            throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED);
        }
        // ④ 注册表动态拼装打标 prompt（维度清单 + JSON 字段说明 + 标题/正文）
        String originalTask = buildLabelPrompt(dto, labelableDims);
        // ⑤ 自校验回喂循环（1 次首轮 + structuredRetryBudget 次回喂，与结构化管道同源）
        int totalAttempts = 1 + agentProperties.getStructuredRetryBudget();
        String lastError = null;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            long start = System.currentTimeMillis();
            // 首轮用原始任务 prompt；回喂轮用校验错误反馈模板（最小反思闭环）
            String userPrompt = attempt == 1 ? originalTask : String.format(
                    AiPromptConstants.STRUCTURED_RETRY_FEEDBACK, lastError, originalTask);
            try {
                TextChatResult result = textModelProvider.chat(TextChatRequest.builder()
                        .systemPrompt(AiPromptConstants.XIAOYUE_SYSTEM_PROMPT)
                        .userPrompt(userPrompt)
                        .temperature(0.0D)
                        .build());
                recordSuccess(dto, attempt, result);
                // service 侧校验（不依赖模型自觉）：词表维度越表拒绝、语义维度空白置空
                Map<String, String> labels = validateAndCollect(result.getContent(), labelableDims);
                metrics.recordGeneration(LABEL_SCENE, AiMetrics.RESULT_SUCCESS);
                log.info("索引期打标完成 requestId={} attempt={} labels={}",
                        dto.getRequestId(), attempt, labels.keySet());
                return wrap(labels);
            } catch (BizException e) {
                // 模型通道失败：失败也留明细；重试预算内再试，预算耗尽 fail-fast 上抛
                recordFail(dto, attempt);
                metrics.recordGeneration(LABEL_SCENE, AiMetrics.RESULT_FAIL);
                lastError = "模型调用失败：" + e.getMessage();
            } catch (IllegalArgumentException e) {
                // 校验不过：模型调用本身成功（已计量），记录具体错误回喂重试
                log.info("打标输出校验未通过 requestId={} attempt={} error={}",
                        dto.getRequestId(), attempt, e.getMessage());
                metrics.recordGeneration(LABEL_SCENE, AiMetrics.RESULT_FAIL);
                lastError = e.getMessage();
            } finally {
                metrics.recordLatency(AiPurposeEnum.LABELING.getCode(), System.currentTimeMillis() - start);
            }
        }
        // ⑥ 重试预算耗尽：fail-fast 上抛（重试语义由 search 消费侧承担）
        log.warn("索引期打标终态失败（重试预算耗尽）requestId={}", dto.getRequestId());
        throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "打标输出校验未通过：" + lastError);
    }

    /**
     * 解析可标注维度清单（filter 词表型 + semantic 型；range/param 型不可标注被剔除）
     *
     * @return 维度名 → 维度定义（保持注册表顺序）
     *
     * <p>【通俗说明】从注册表里挑出"AI 能打的维度"：filter 词表型和 semantic 语义型
     * 可以交给 AI；range 和 param 型不归 AI 管（前者索引期直接算，后者是请求参数），
     * 直接剔除。</p>
     */
    private Map<String, Dimension> resolveLabelableDimensions() {
        Map<String, Dimension> result = new LinkedHashMap<>();
        for (Map.Entry<String, Dimension> entry : dimensionsProperties.getDimensions().entrySet()) {
            String match = entry.getValue().getMatch();
            // 仅 filter 与 semantic 维度可标注：range 由索引期纯计算、param 为请求参数不入标签
            if ("filter".equals(match) || "semantic".equals(match)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * 注册表动态拼装打标 prompt（维度清单逐行 + JSON 字段说明）
     *
     * @param dto          打标请求
     * @param labelableDims 可标注维度清单
     * @return 完整打标 prompt
     *
     * <p>【通俗说明】按模板拼打标 prompt：把每个维度写成一行说明（是"选择题"
     * 就列出可选项，是"填空题"就注明自由发挥），再附上 JSON 输出格式说明和
     * 文章标题、正文。维度变了不用改代码，改配置注册表即可。</p>
     */
    private String buildLabelPrompt(AiLabelRequestDTO dto, Map<String, Dimension> labelableDims) {
        // 变量：维度清单行（每行「维度名（说明）：受控词表取值 / 自由语义」）
        StringBuilder dimLines = new StringBuilder();
        // 变量：JSON 字段说明（"style":"词表值或null" 形式逐字段拼接）
        StringBuilder jsonFields = new StringBuilder();
        for (Map.Entry<String, Dimension> entry : labelableDims.entrySet()) {
            Dimension dim = entry.getValue();
            String valuesNote = dim.getValues() == null || dim.getValues().isEmpty()
                    ? "自由语义短语" : "受控词表：" + String.join("/", dim.getValues());
            dimLines.append("- ").append(entry.getKey()).append("（").append(dim.getPrompt())
                    .append("）——").append(valuesNote).append("\n");
            if (jsonFields.length() > 0) {
                jsonFields.append(",");
            }
            jsonFields.append("\"").append(entry.getKey()).append("\":\"词表值或短语或null\"");
        }
        return String.format(AiPromptConstants.AI_LABEL_PROMPT_FRAME,
                dimLines.toString(), jsonFields.toString(), dto.getTitle(), dto.getContent());
    }

    /**
     * 校验并收集标签（越表值拒绝抛 IllegalArgumentException 回喂重试；低置信缺失不硬造）
     *
     * @param rawText       模型原始输出
     * @param labelableDims 可标注维度清单（校验依据）
     * @return 校验通过的维度标签集（无置信标签为空映射）
     * @throws IllegalArgumentException 解析失败或越表（异常消息即回喂给模型的具体错误）
     *
     * <p>【通俗说明】逐维度检查 AI 交的作业：没答的维度跳过（低置信不硬造）；答了
     * 的先去首尾空白，再看这维度是不是"选择题"——是就必须在词表里，选了词表外的
     * 选项直接报错（错误信息会原样喂回给模型重做）。</p>
     */
    private Map<String, String> validateAndCollect(String rawText, Map<String, Dimension> labelableDims) {
        JsonNode node = parseJson(rawText);
        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, Dimension> entry : labelableDims.entrySet()) {
            String dimName = entry.getKey();
            String value = node.path(dimName).isMissingNode() || node.path(dimName).isNull()
                    ? null : node.path(dimName).asText(null);
            // 空值 = 低置信未标注：跳过（不硬造）
            if (value == null || value.isBlank()) {
                continue;
            }
            value = value.trim();
            // 受控词表维度越表校验：不在词表内即拒绝（AI 不新造标签值）
            if (!entry.getValue().getValues().isEmpty() && !entry.getValue().getValues().contains(value)) {
                throw new IllegalArgumentException("维度 " + dimName + " 取值「" + value
                        + "」不在受控词表内，只能取：" + String.join("/", entry.getValue().getValues()));
            }
            labels.put(dimName, value);
        }
        return labels;
    }

    /**
     * 解析模型输出为 JSON（容忍 ```json 围栏包裹与前后夹带说明文字）
     *
     * @param rawText 模型原始输出
     * @return JSON 节点
     * @throws IllegalArgumentException 解析失败（异常消息即回喂给模型的具体错误）
     *
     * <p>【通俗说明】剥掉可能的说明文字后，取第一个 { 到最后一个 } 之间的内容
     * 当 JSON 解析，解析不动就报错让模型重说。</p>
     */
    private JsonNode parseJson(String rawText) {
        // 剥围栏后截取首个 { 到最后一个 } 之间的内容（容忍模型在 JSON 前后夹带说明文字）
        String candidate = rawText == null ? "" : rawText.trim();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("输出不是 JSON 对象，请只输出 JSON");
        }
        try {
            return objectMapper.readTree(candidate.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败：" + e.getMessage());
        }
    }

    /**
     * 打标成功计量留痕（purpose=LABELING，无发起用户；重试轮次按 callSeq 递增）
     *
     * @param dto     打标请求（requestId 计量幂等键）
     * @param callSeq 调用序号（重试轮次）
     * @param result  模型返回（token 快照）
     *
     * <p>【通俗说明】成功记账：purpose 固定 LABELING、没有发起用户，第几轮重试
     * 就记 callSeq 为几，同时打一条成功指标。</p>
     */
    private void recordSuccess(AiLabelRequestDTO dto, int callSeq, TextChatResult result) {
        usageRecordService.recordSuccess(AiUsageDetail.builder()
                .requestId(dto.getRequestId())
                .callSeq(callSeq)
                .userId(null)
                .purpose(AiPurposeEnum.LABELING)
                .scene(LABEL_SCENE)
                .modelVersion(result.getModelVersion())
                .tokenIn(result.getTokenIn())
                .tokenOut(result.getTokenOut())
                .degraded(0)
                .occurredAt(LocalDateTime.now())
                .build());
        metrics.recordCall(AiPurposeEnum.LABELING.getCode(), providerProperties.getProviderName(),
                AiMetrics.RESULT_SUCCESS);
    }

    /**
     * 打标失败计量留痕（失败即无成本防护：token 按 0 落库）
     *
     * @param dto     打标请求（requestId 计量幂等键）
     * @param callSeq 调用序号（重试轮次）
     *
     * <p>【通俗说明】失败记账：token 未知按 0 落库（失败即无成本防护），不因失败
     * 断掉计量链路。</p>
     */
    private void recordFail(AiLabelRequestDTO dto, int callSeq) {
        usageRecordService.recordFail(AiUsageDetail.builder()
                .requestId(dto.getRequestId())
                .callSeq(callSeq)
                .userId(null)
                .purpose(AiPurposeEnum.LABELING)
                .scene(LABEL_SCENE)
                .modelVersion(providerProperties.getModel())
                .tokenIn(null)
                .tokenOut(null)
                .degraded(0)
                .occurredAt(LocalDateTime.now())
                .build());
        metrics.recordCall(AiPurposeEnum.LABELING.getCode(), providerProperties.getProviderName(),
                AiMetrics.RESULT_FAIL);
    }

    /**
     * 空标签响应（注册表不可用降级路径）
     *
     * @return labels 为空映射的响应
     *
     * <p>【通俗说明】注册表不可用时的降级返回：空标签集，不报错不硬造。</p>
     */
    private AiLabelResponseDTO emptyResponse() {
        AiLabelResponseDTO resp = new AiLabelResponseDTO();
        resp.setLabels(new LinkedHashMap<>());
        return resp;
    }

    /**
     * 包装标签集为响应 DTO
     *
     * @param labels 校验通过的标签集
     * @return 响应 DTO
     *
     * <p>【通俗说明】把校验通过的标签集装进响应对象返回。</p>
     */
    private AiLabelResponseDTO wrap(Map<String, String> labels) {
        AiLabelResponseDTO resp = new AiLabelResponseDTO();
        resp.setLabels(labels);
        return resp;
    }
}
