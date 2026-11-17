package com.litisle.litisleai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.config.AiRagProperties;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.dto.ExtractedSearchCondition;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.provider.text.TextChatRequest;
import com.litisle.litisleai.provider.text.TextChatResult;
import com.litisle.litisleai.provider.text.TextModelProvider;
import com.litisle.litisleai.service.support.AiUsageDetail;
import com.litisle.litisleapi.config.AiLabelDimensionsProperties;
import com.litisle.litisleapi.config.AiLabelDimensionsProperties.Dimension;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构化条件提取服务（板块八 1.2：固定管道 1 次 LLM 输出统一 JSON，不用 Agent 循环——
 * 条件提取是单轮任务，循环是纯成本浪费）
 *
 * <p>【职责边界】本类收口提取链路：预算判定 → 注册表动态拼装提取 prompt → 1 次 LLM 输出
 * 条件 JSON → service 侧按注册表校验（越枚举/min&gt;max/起止倒置/topK 超上限 → 具体错误回喂
 * 重试 1 次，复用 structuredRetryBudget 模式）→ 返回结构化条件。作者名 → ID 解析与条件版检索
 * 由调用方（SimilarRecommendService）编排，本类不做 Feign 调用。</p>
 *
 * <p>【动态拼装】提取 prompt 与输出 schema 由维度注册表动态生成：filter 维度逐字段带受控词表、
 * semantic 维度自由短语；新维度改注册表配置零代码。阅读时长按 ~400 字/分钟换算为 wordCount
 * 区间的规则写入 prompt（不加独立字段）。</p>
 *
 * <p>【计量口径】purpose=SIMILAR（查询期生成，走既有相似推荐桶）；失败也落明细（callSeq
 * 按重试轮次递增）。失败语义：预算超限 / 重试耗尽抛 BizException，由调用方降级 L0 不报错。</p>
 *
 * <p>【通俗说明】"把人话翻译成检索条件"的翻译官：用户说"找最近一周关于养生的千字
 * 左右文章"，本类把它变成结构化条件（时间范围、标签、字数区间等）。做法是固定管道
 * ——只调一次模型（单轮任务用 Agent 循环纯属浪费钱），输出 JSON 后由程序逐项检查
 * （分类是否在词表里、字数区间是否颠倒、日期是否合法、条数是否超上限），不合格就把
 * 具体错误回喂让模型重试一次。要提取哪些维度不写死在代码里，由维度注册表配置动态
 * 生成 prompt——加新筛选维度只改配置。会话里支持增量提取：上一轮条件作为基线写进
 * prompt，用户只说"改成科幻"就能在原条件上微调。翻译失败时上抛异常，由调用方
 * 降级为不带条件的普通检索（L0）。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConditionExtractService {

    /** 提取调用计量场景快照 */
    private static final String EXTRACT_SCENE = "condition-extract";

    /** 条件提取输出固定字段名：主旨语义文本 */
    private static final String FIELD_QUERY_TEXT = "queryText";

    /** 提取输出固定字段名：指定作者笔名 */
    private static final String FIELD_AUTHOR_NAME = "authorName";

    /** 提取输出固定字段名：需排除的作者笔名 */
    private static final String FIELD_EXCLUDE_AUTHOR_NAME = "excludeAuthorName";

    /** 提取输出固定字段名：分类 */
    private static final String FIELD_CATEGORY = "category";

    /** 提取输出固定字段名：标签数组 */
    private static final String FIELD_TAGS = "tags";

    /** 提取输出固定字段名：字数下限 */
    private static final String FIELD_WORD_COUNT_MIN = "wordCountMin";

    /** 提取输出固定字段名：字数上限 */
    private static final String FIELD_WORD_COUNT_MAX = "wordCountMax";

    /** 提取输出固定字段名：发布时间起 */
    private static final String FIELD_TIME_START = "timeStart";

    /** 提取输出固定字段名：发布时间止 */
    private static final String FIELD_TIME_END = "timeEnd";

    /** 提取输出固定字段名：返回条数 */
    private static final String FIELD_TOP_K = "topK";

    /** 提取输出固定字段名：排序意图 */
    private static final String FIELD_SORT_HINT = "sortHint";

    /** 文本模型通道（enabled=false 时调用抛明确异常，由调用方降级 L0） */
    private final TextModelProvider textModelProvider;

    /** 计量明细服务 */
    private final AiUsageRecordService usageRecordService;

    /** 预算分账判定（SIMILAR 桶） */
    private final AiBudgetService budgetService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** 通道配置（model 版本快照 / provider 标签） */
    private final AiProviderProperties providerProperties;

    /** Agent 配置（结构化重试预算） */
    private final AiAgentProperties agentProperties;

    /** 维度注册表（提取 prompt 动态拼装 + 词表校验唯一数据源） */
    private final AiLabelDimensionsProperties dimensionsProperties;

    /** RAG 配置（条件版 topK 上限取 similar.conditionalTopKMax） */
    private final AiRagProperties ragProperties;

    /** JSON 解析器 */
    private final ObjectMapper objectMapper;

    /**
     * 条件提取主流程（1 次 LLM 固定管道）
     *
     * <p>【业务逻辑步骤】
     * 1. 注册表可用性检查（enabled=false → 抛异常由调用方降级 L0）；
     * 2. 预算判定（SIMILAR 桶 EXCEEDED → 抛异常由调用方降级 L0）；
     * 3. 注册表动态拼装提取 prompt（含当前日期供相对时间换算）；
     * 4. 1 次 LLM 输出条件 JSON，service 侧按注册表校验（越枚举/区间倒置/topK 超上限回喂重试 1 次）；
     * 5. 校验通过组装结构化条件（semantic 维度并入 queryText）。</p>
     *
     * @param requestId 计量幂等键（透传自相似推荐请求）
     * @param userId    发起用户 ID（计量归属；匿名浏览为 null）
     * @param scene     业务场景快照（计量明细留痕）
     * @param freeText  用户自由条件描述
     * @return 结构化检索条件
     * @throws BizException 注册表不可用 / 预算超限 / 通道失败且重试耗尽 / 输出校验终态不过（调用方降级 L0）
     *
     * <p>【通俗说明】无会话上下文的简化入口：直接按首轮口径提取。</p>
     */
    public ExtractedSearchCondition extract(String requestId, Long userId, String scene, String freeText) {
        // 无上一轮条件：按普通提取处理（板块十 2.1 首轮口径）
        return extract(requestId, userId, scene, freeText, null);
    }

    /**
     * 条件提取主流程（带合并基线重载，板块十 2.1：会话内多轮调条件）
     *
     * <p>【业务逻辑步骤】
     * 1. 注册表可用性检查（enabled=false → 抛异常由调用方降级 L0）；
     * 2. 预算判定（SIMILAR 桶 EXCEEDED → 抛异常由调用方降级 L0）；
     * 3. 注册表动态拼装提取 prompt——有上一轮条件 JSON 时走增量提取模板（合并基线入 prompt，
     *    输出合并后的完整条件 JSON，仍是 1 次 LLM），无则按普通提取；
     * 4. 1 次 LLM 输出条件 JSON，service 侧按注册表校验（越枚举/区间倒置/topK 超上限回喂重试 1 次）；
     * 5. 校验通过组装结构化条件（semantic 维度并入 queryText）。</p>
     *
     * <p>【合并语义】上一轮条件 JSON 作为基线写入 prompt：本轮明确修改/否定的字段以本轮为准
     * 覆盖上轮（否定输出 null），未提及字段沿用上轮；合并冲突由模型按规则消解，service 侧不做
     * 机械合并——校验与普通提取完全同口径（合并产物同样过词表/区间/topK 校验）。</p>
     *
     * @param requestId            计量幂等键（透传自相似推荐请求）
     * @param userId               发起用户 ID（计量归属；匿名浏览为 null）
     * @param scene                业务场景快照（计量明细留痕）
     * @param freeText             用户自由条件描述
     * @param previousConditionJson 上一轮提取的条件 JSON（会话上下文；null=首轮按普通提取）
     * @return 结构化检索条件
     * @throws BizException 注册表不可用 / 预算超限 / 通道失败且重试耗尽 / 输出校验终态不过（调用方降级 L0）
     *
     * <p>【通俗说明】主流程五步：① 注册表关着直接抛错；② 查预算；③ 拼 prompt
     * （带上一轮条件就走增量合并模板）；④ "调模型 → 校验 → 不过回喂重试"循环
     * （每轮无论成败都记账）；⑤ 重试用完还不过就上抛，调用方自动退回 L0 直通。</p>
     */
    public ExtractedSearchCondition extract(String requestId, Long userId, String scene, String freeText,
                                            String previousConditionJson) {
        // ① 注册表可用性：注册表关闭时条件管道整体停用（提取无从谈起，上抛转 L0 降级）
        if (!dimensionsProperties.isEnabled()) {
            throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "维度注册表未启用，条件提取停用");
        }
        // ② 预算判定：SIMILAR 桶超限上抛（调用方降级 L0，可观测记配额指标）
        if (budgetService.check(AiPurposeEnum.SIMILAR) == AiBudgetDecision.EXCEEDED) {
            log.warn("条件提取预算超限 requestId={}", requestId);
            metrics.recordQuotaExceeded(AiPurposeEnum.SIMILAR.getCode());
            throw new BizException(ErrorCode.AI_BUDGET_EXCEEDED);
        }
        // ③ 注册表动态拼装提取 prompt（当前日期供相对时间换算基准；有上一轮条件走增量合并模板）
        String originalTask = previousConditionJson == null || previousConditionJson.isBlank()
                ? buildExtractPrompt(freeText)
                : buildIncrementExtractPrompt(freeText, previousConditionJson);
        // ④ 自校验回喂循环（1 次首轮 + structuredRetryBudget 次回喂）
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
                recordSuccess(requestId, userId, scene, attempt, result);
                // service 侧校验（不依赖模型自觉）：词表越枚举 / 区间倒置 / topK 超上限
                ExtractedSearchCondition condition = validateAndCollect(result.getContent());
                metrics.recordGeneration(normalizeScene(scene), AiMetrics.RESULT_SUCCESS);
                log.info("条件提取完成 requestId={} attempt={} hasCondition={}",
                        requestId, attempt, condition.hasAnyCondition());
                return condition;
            } catch (BizException e) {
                // 模型通道失败：失败也留明细；重试预算内再试，预算耗尽上抛转调用方 L0 降级
                recordFail(requestId, userId, scene, attempt);
                metrics.recordGeneration(normalizeScene(scene), AiMetrics.RESULT_FAIL);
                lastError = "模型调用失败：" + e.getMessage();
            } catch (IllegalArgumentException e) {
                // 校验不过：模型调用本身成功（已计量），记录具体错误回喂重试
                log.info("条件提取输出校验未通过 requestId={} attempt={} error={}",
                        requestId, attempt, e.getMessage());
                metrics.recordGeneration(normalizeScene(scene), AiMetrics.RESULT_FAIL);
                lastError = e.getMessage();
            } finally {
                metrics.recordLatency(AiPurposeEnum.SIMILAR.getCode(), System.currentTimeMillis() - start);
            }
        }
        // ⑤ 重试预算耗尽：上抛转调用方 L0 降级（不报错给用户）
        log.warn("条件提取终态失败（重试预算耗尽）requestId={}", requestId);
        throw new BizException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "条件提取未通过：" + lastError);
    }

    /**
     * 注册表动态拼装条件增量提取 prompt（板块十 2.1：上一轮条件 JSON 为合并基线，
     * 输出合并后的完整条件 JSON；维度清单与普通提取共用同一注册表拼装）
     *
     * @param freeText              用户本轮修改描述
     * @param previousConditionJson 上一轮提取的条件 JSON（合并基线）
     * @return 完整增量提取 prompt
     *
     * <p>【通俗说明】拼增量模板：把上一轮条件 JSON 作为"基线"写进 prompt，
     * 模型按规则输出合并后的完整条件。</p>
     */
    private String buildIncrementExtractPrompt(String freeText, String previousConditionJson) {
        return String.format(AiPromptConstants.CONDITION_EXTRACT_INCREMENT_PROMPT_FRAME,
                buildDimensionLines(), LocalDate.now(), previousConditionJson, freeText);
    }

    /**
     * 注册表维度清单拼装（普通提取与增量提取共用：filter 带受控词表、semantic 自由短语、
     * 固定字段在前、sort 词表注册表驱动）
     *
     * @return 维度清单文本（JSON 字段说明逐行）
     *
     * <p>【通俗说明】拼"JSON 每个字段是什么意思"的说明书：固定字段写死说明，
     * 注册表维度按配置逐行生成（filter 型列出可选词表、semantic 型注明自由发挥），
     * 最后附上字数/时间/条数/排序字段的说明。</p>
     */
    private String buildDimensionLines() {
        // 变量：维度清单行（JSON 字段说明，固定字段在前，注册表维度在后）
        StringBuilder dimLines = new StringBuilder();
        dimLines.append("- queryText：检索主旨语义文本（保留用户主旨与风格表达，含否定语义）\n");
        dimLines.append("- authorName：指定作者笔名（用户点名「作者 X 写的」时提取）\n");
        dimLines.append("- excludeAuthorName：需排除的作者笔名（「不要作者 X 的」时提取）\n");
        // 注册表维度逐行拼装：filter 带受控词表、semantic 自由短语（新维度改配置零代码）
        for (Map.Entry<String, Dimension> entry : dimensionsProperties.getDimensions().entrySet()) {
            Dimension dim = entry.getValue();
            String match = dim.getMatch();
            if ("filter".equals(match)) {
                // filter 维度：受控词表为空时说明取既有字段值（category/tags），否则列出词表
                String valuesNote = dim.getValues() == null || dim.getValues().isEmpty()
                        ? "站内既有" + dim.getPrompt() : "受控词表：" + String.join("/", dim.getValues());
                dimLines.append("- ").append(entry.getKey()).append("（").append(dim.getPrompt())
                        .append("）：").append(valuesNote).append("\n");
            } else if ("semantic".equals(match)) {
                // semantic 维度：自由语义短语，提取后由 service 并入 queryText
                dimLines.append("- ").append(entry.getKey()).append("（").append(dim.getPrompt())
                        .append("）：自由语义短语\n");
            }
        }
        dimLines.append("- wordCountMin/wordCountMax：正文字数区间（阅读时长按约 400 字/分钟换算）\n");
        dimLines.append("- timeStart/timeEnd：发布时间区间（yyyy-MM-dd）\n");
        dimLines.append("- topK：返回条数（数字）\n");
        // param 型 sort 维度词表同样由注册表驱动（注册表缺 sort 维度时给默认枚举说明）
        Dimension sortDim = dimensionsProperties.getDimensions().get("sort");
        String sortValues = sortDim != null && sortDim.getValues() != null && !sortDim.getValues().isEmpty()
                ? String.join("/", sortDim.getValues()) : "related/newest/heat/similarity-first";
        dimLines.append("- sortHint：排序意图，只能取：").append(sortValues).append("\n");
        return dimLines.toString();
    }

    /**
     * 注册表动态拼装条件提取 prompt（固定字段说明 + 注册表维度清单 + 当前日期 + 用户描述）
     *
     * @param freeText 用户自由条件描述
     * @return 完整提取 prompt
     *
     * <p>【通俗说明】拼首轮提取模板：维度说明书 + 当前日期（供"最近一周"这类相对
     * 时间换算）+ 用户的原话。</p>
     */
    private String buildExtractPrompt(String freeText) {
        return String.format(AiPromptConstants.CONDITION_EXTRACT_PROMPT_FRAME,
                buildDimensionLines(), LocalDate.now(), freeText);
    }

    /**
     * 校验并收集条件（越枚举/区间倒置/topK 超上限抛 IllegalArgumentException 回喂重试）
     *
     * @param rawText 模型原始输出
     * @return 结构化检索条件（全字段空 = 无有效条件，调用方降级 L0）
     * @throws IllegalArgumentException 解析失败或校验不过（异常消息即回喂给模型的具体错误）
     *
     * <p>【通俗说明】逐项质检：文本字段取出来（空白视为"没提"）→ 注册表维度按类型
     * 处理（filter 型越词表即拒、semantic 型并进检索文本）→ 字数区间查负数和颠倒 →
     * 时间查格式和倒置 → 条数查范围 → 排序查词表。任何一项不过都抛出带具体数字的
     * 错误，模型照着改就行。</p>
     */
    private ExtractedSearchCondition validateAndCollect(String rawText) {
        JsonNode node = parseJson(rawText);
        ExtractedSearchCondition condition = new ExtractedSearchCondition();
        // ① 固定文本字段：queryText / 作者名 / 分类 / 标签（空白统一置 null，全字段空即无有效条件）
        condition.setQueryText(textOrNull(node, FIELD_QUERY_TEXT));
        condition.setAuthorName(textOrNull(node, FIELD_AUTHOR_NAME));
        condition.setExcludeAuthorName(textOrNull(node, FIELD_EXCLUDE_AUTHOR_NAME));
        condition.setCategory(textOrNull(node, FIELD_CATEGORY));
        condition.setTags(textListOrNull(node, FIELD_TAGS));
        // ② 注册表维度：filter 收进 aiLabels（越枚举拒绝）；semantic 并入 queryText
        StringBuilder semanticText = new StringBuilder();
        for (Map.Entry<String, Dimension> entry : dimensionsProperties.getDimensions().entrySet()) {
            String dimName = entry.getKey();
            Dimension dim = entry.getValue();
            String value = textOrNull(node, dimName);
            if (value == null) {
                continue;
            }
            if ("filter".equals(dim.getMatch())) {
                // 受控词表维度越表校验：不在词表内即拒绝（防模型造词污染过滤条件）
                if (dim.getValues() != null && !dim.getValues().isEmpty() && !dim.getValues().contains(value)) {
                    throw new IllegalArgumentException("字段 " + dimName + " 取值「" + value
                            + "」不在受控词表内，只能取：" + String.join("/", dim.getValues()));
                }
                condition.getAiLabels().put(dimName, value);
            } else if ("semantic".equals(dim.getMatch())) {
                // semantic 维度并入检索语义文本（topic 等，与 queryText 用换行拼接）
                if (semanticText.length() > 0) {
                    semanticText.append("\n");
                }
                semanticText.append(value);
            }
        }
        // 语义维度拼接（queryText 在前，semantic 补充在后）
        if (semanticText.length() > 0) {
            condition.setQueryText(condition.getQueryText() == null
                    ? semanticText.toString() : condition.getQueryText() + "\n" + semanticText);
        }
        // ③ wordCount 区间：非负 + min>max 拒绝（防模型倒置区间污染 range 查询）
        condition.setWordCountMin(longOrNull(node, FIELD_WORD_COUNT_MIN));
        condition.setWordCountMax(longOrNull(node, FIELD_WORD_COUNT_MAX));
        if (condition.getWordCountMin() != null && condition.getWordCountMin() < 0
                || condition.getWordCountMax() != null && condition.getWordCountMax() < 0) {
            throw new IllegalArgumentException("wordCountMin/wordCountMax 不能为负数");
        }
        if (condition.getWordCountMin() != null && condition.getWordCountMax() != null
                && condition.getWordCountMin() > condition.getWordCountMax()) {
            throw new IllegalArgumentException("wordCountMin（" + condition.getWordCountMin()
                    + "）不能大于 wordCountMax（" + condition.getWordCountMax() + "），请修正区间");
        }
        // ④ timeRange：可解析 + 起止倒置拒绝（无法解析视为编造输出，回喂重试）
        condition.setTimeStart(textOrNull(node, FIELD_TIME_START));
        condition.setTimeEnd(textOrNull(node, FIELD_TIME_END));
        if (condition.getTimeStart() != null && !isParseableDate(condition.getTimeStart())) {
            throw new IllegalArgumentException("timeStart「" + condition.getTimeStart()
                    + "」不是合法日期，请输出 yyyy-MM-dd 格式");
        }
        if (condition.getTimeEnd() != null && !isParseableDate(condition.getTimeEnd())) {
            throw new IllegalArgumentException("timeEnd「" + condition.getTimeEnd()
                    + "」不是合法日期，请输出 yyyy-MM-dd 格式");
        }
        if (condition.getTimeStart() != null && condition.getTimeEnd() != null
                && LocalDate.parse(condition.getTimeStart()).isAfter(LocalDate.parse(condition.getTimeEnd()))) {
            throw new IllegalArgumentException("timeStart（" + condition.getTimeStart()
                    + "）不能晚于 timeEnd（" + condition.getTimeEnd() + "），请修正区间");
        }
        // ⑤ topK：正数 + 上限校验（超上限回喂重试，上限与 search 侧截断口径对齐）
        Integer topK = intOrNull(node, FIELD_TOP_K);
        if (topK != null) {
            int maxTopK = Math.max(1, ragProperties.getSimilar().getConditionalTopKMax());
            if (topK < 1 || topK > maxTopK) {
                throw new IllegalArgumentException("topK=" + topK + " 超出范围（1-" + maxTopK + "），请修正");
            }
            condition.setTopK(topK);
        }
        // ⑥ sortHint：注册表 sort 词表校验（注册表缺 sort 维度时忽略该字段，不硬编码枚举）
        String sortHint = textOrNull(node, FIELD_SORT_HINT);
        if (sortHint != null) {
            Dimension sortDim = dimensionsProperties.getDimensions().get("sort");
            if (sortDim != null && sortDim.getValues() != null && !sortDim.getValues().isEmpty()
                    && !sortDim.getValues().contains(sortHint)) {
                throw new IllegalArgumentException("sortHint「" + sortHint + "」不在取值范围："
                        + String.join("/", sortDim.getValues()));
            }
            condition.setSortHint(sortHint);
        }
        return condition;
    }

    /**
     * 解析模型输出为 JSON（容忍 ```json 围栏包裹与前后夹带说明文字）
     *
     * @param rawText 模型原始输出
     * @return JSON 节点
     * @throws IllegalArgumentException 解析失败（异常消息即回喂给模型的具体错误）
     *
     * <p>【通俗说明】掐头去尾取 JSON：从第一个 { 到最后一个 }，把模型夹带的
     * "好的，以下是提取结果"之类废话扔掉。</p>
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
     * 取文本字段（缺失/null/空白统一返回 null——「未提及输出 null」口径的校验侧兜底）
     *
     * @param node      JSON 节点
     * @param fieldName 字段名
     * @return 非空白文本；缺失或空白返回 null
     *
     * <p>【通俗说明】取文本字段：没有、为 null 或全是空白都统一算"没提"。</p>
     */
    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        String value = field.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 取字符串数组字段（过滤空白项）
     *
     * @param node      JSON 节点
     * @param fieldName 字段名
     * @return 非空数组；缺失或全空白返回 null
     *
     * <p>【通俗说明】取字符串数组字段：过滤掉空白项，剩不下东西就当没提。</p>
     */
    private List<String> textListOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (!field.isArray() || field.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : field) {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values.isEmpty() ? null : values;
    }

    /**
     * 取整数字段（非法值按 null 处理，宽松容错——数字校验集中在专用分支）
     *
     * @param node      JSON 节点
     * @param fieldName 字段名
     * @return 整数值；缺失或非法返回 null
     *
     * <p>【通俗说明】取 Long 数字字段：类型不对按"没提"处理，宽松容错（数值本身的
     * 合法性由专用校验分支负责）。</p>
     */
    private Long longOrNull(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (field.isMissingNode() || field.isNull()) {
            return null;
        }
        try {
            return field.asLong();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取整数字段（Integer 形态，topK 校验用）
     *
     * @param node      JSON 节点
     * @param fieldName 字段名
     * @return 整数值；缺失或非法返回 null
     *
     * <p>【通俗说明】Integer 形态的取数（内部复用 longOrNull）。</p>
     */
    private Integer intOrNull(JsonNode node, String fieldName) {
        Long value = longOrNull(node, fieldName);
        return value == null ? null : value.intValue();
    }

    /**
     * 判断日期字符串可解析（yyyy-MM-dd 或完整 ISO 日期时间均接受）
     *
     * @param value 日期字符串
     * @return true=可解析
     *
     * <p>【通俗说明】试试能不能把字符串解析成日期（两种格式都试），解析不动就算
     * 模型编造。</p>
     */
    private boolean isParseableDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            try {
                LocalDateTime.parse(value);
                return true;
            } catch (DateTimeParseException e2) {
                return false;
            }
        }
    }

    /**
     * 提取成功计量留痕（purpose=SIMILAR 查询期生成；重试轮次按 callSeq 递增）
     *
     * @param requestId 计量幂等键
     * @param userId    发起用户 ID（匿名为 null）
     * @param scene     业务场景快照
     * @param callSeq   调用序号
     * @param result    模型返回（token 快照）
     *
     * <p>【通俗说明】成功记账：purpose 固定 SIMILAR，第几轮就记 callSeq 为几。</p>
     */
    private void recordSuccess(String requestId, Long userId, String scene, int callSeq, TextChatResult result) {
        usageRecordService.recordSuccess(AiUsageDetail.builder()
                .requestId(requestId)
                .callSeq(callSeq)
                .userId(userId)
                .purpose(AiPurposeEnum.SIMILAR)
                .scene(normalizeScene(scene))
                .modelVersion(result.getModelVersion())
                .tokenIn(result.getTokenIn())
                .tokenOut(result.getTokenOut())
                .degraded(0)
                .occurredAt(LocalDateTime.now())
                .build());
        metrics.recordCall(AiPurposeEnum.SIMILAR.getCode(), providerProperties.getProviderName(),
                AiMetrics.RESULT_SUCCESS);
    }

    /**
     * 提取失败计量留痕（失败即无成本防护：token 按 0 落库）
     *
     * @param requestId 计量幂等键
     * @param userId    发起用户 ID（匿名为 null）
     * @param scene     业务场景快照
     * @param callSeq   调用序号
     *
     * <p>【通俗说明】失败记账：token 未知按 0 落库，账本不断链。</p>
     */
    private void recordFail(String requestId, Long userId, String scene, int callSeq) {
        usageRecordService.recordFail(AiUsageDetail.builder()
                .requestId(requestId)
                .callSeq(callSeq)
                .userId(userId)
                .purpose(AiPurposeEnum.SIMILAR)
                .scene(normalizeScene(scene))
                .modelVersion(providerProperties.getModel())
                .tokenIn(null)
                .tokenOut(null)
                .degraded(0)
                .occurredAt(LocalDateTime.now())
                .build());
        metrics.recordCall(AiPurposeEnum.SIMILAR.getCode(), providerProperties.getProviderName(),
                AiMetrics.RESULT_FAIL);
    }

    /**
     * 场景标签归一化（null 兜底 default，防指标空标签）
     *
     * @param scene 原始场景
     * @return 归一化场景标签
     *
     * <p>【通俗说明】场景没传就记成 default，避免监控指标出现空标签。</p>
     */
    private String normalizeScene(String scene) {
        return scene == null || scene.isBlank() ? AiMetrics.SCENE_DEFAULT : scene;
    }
}
