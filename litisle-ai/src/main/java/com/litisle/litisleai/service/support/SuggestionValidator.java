package com.litisle.litisleai.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import com.litisle.litisleapi.dto.content.TagSuggestDTO;
import com.litisle.litisleapi.feign.ContentClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化建议校验器（板块二 2.4：校验规则在 service 侧执行，不依赖模型自觉）
 *
 * <p>【校验规则】SUMMARY：summary 非空且 ≤ summaryMaxChars；TAGGING：tags 非空、
 * 数量 ≤ maxTagCount 且逐一与标签池精确比对（AI 不新造标签，prompt + 越池校验双重约束）；
 * QA：回答非空。解析失败或校验不过抛 IllegalArgumentException（携带具体错误信息，
 * 由调用方组装回喂重试 prompt）。</p>
 *
 * <p>【解析容错】容忍模型输出包裹 ```json 代码围栏；围栏剥离后再解析，
 * 解析仍失败即视为未通过（进入回喂重试）。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuggestionValidator {

    /** 内容服务客户端（标签越池校验经标签池精确比对） */
    private final ContentClient contentClient;

    /** Agent 配置（摘要字数上限 / 标签数量上限） */
    private final AiAgentProperties agentProperties;

    /** JSON 解析器 */
    private final ObjectMapper objectMapper;

    /**
     * 校验结果载体（按用途填充对应字段，其余为 null）
     */
    @Data
    public static class ValidatedSuggestion {
        /** 摘要建议（SUMMARY） */
        private String summary;
        /** 标签建议（TAGGING，全部池内） */
        private List<String> tags;
        /** 问答回答（QA） */
        private String answer;
        /** 润色建议（POLISH，板块三 3.3） */
        private String polished;
        /** 结构分析建议（STRUCTURE，板块三 3.3） */
        private String analysis;
    }

    /**
     * 推荐理由条目载体（板块五 5.2：index 对应候选序号 1 起，reason 为一句话理由）
     */
    @Data
    public static class ReasonItem {
        /** 候选序号（1 起，对应推荐卡片列表位置） */
        private int index;
        /** 一句话推荐理由 */
        private String reason;

        public ReasonItem(int index, String reason) {
            this.index = index;
            this.reason = reason;
        }
    }

    /**
     * 解析并校验模型原始输出（2.4 自校验闭环的校验侧）
     *
     * @param purpose  用途（决定校验规则与字段）
     * @param rawText  模型原始输出文本
     * @return 校验通过的建议内容
     * @throws IllegalArgumentException 解析失败或校验不过（异常消息即回喂给模型的具体错误）
     */
    public ValidatedSuggestion validate(AiPurposeEnum purpose, String rawText) {
        // 空输出直接判失败（回喂重试由调用方处理）
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("模型输出为空");
        }
        return switch (purpose) {
            case SUMMARY -> validateSummary(rawText);
            case TAGGING -> validateTags(rawText);
            case QA -> validateAnswer(rawText);
            case POLISH -> validateTextField(rawText, "polished", "润色建议");
            case STRUCTURE -> validateTextField(rawText, "analysis", "结构分析建议");
            default -> throw new IllegalArgumentException("用途 " + purpose.getCode() + " 不支持结构化校验");
        };
    }

    /**
     * 摘要校验：解析 JSON 取 summary 字段，非空且 ≤ 上限（超出按具体超出量提示，便于模型修正）
     */
    private ValidatedSuggestion validateSummary(String rawText) {
        JsonNode node = parseJson(rawText);
        String summary = node.path("summary").asText(null);
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("缺少 summary 字段或内容为空");
        }
        int maxChars = agentProperties.getSummaryMaxChars();
        if (summary.length() > maxChars) {
            throw new IllegalArgumentException("摘要超出 " + maxChars + " 字上限（当前 " + summary.length() + " 字），请压缩后重新输出");
        }
        ValidatedSuggestion result = new ValidatedSuggestion();
        result.setSummary(summary);
        return result;
    }

    /**
     * 标签校验：解析 JSON 取 tags 数组，数量上限 + 逐一与标签池精确比对（越池即拒绝）
     */
    private ValidatedSuggestion validateTags(String rawText) {
        JsonNode node = parseJson(rawText);
        JsonNode tagsNode = node.path("tags");
        if (!tagsNode.isArray() || tagsNode.isEmpty()) {
            throw new IllegalArgumentException("缺少 tags 数组字段或数组为空");
        }
        List<String> tags = new ArrayList<>();
        List<String> outOfPool = new ArrayList<>();
        for (JsonNode tagNode : tagsNode) {
            String tag = tagNode.asText(null);
            if (tag == null || tag.isBlank()) {
                continue;
            }
            tags.add(tag);
        }
        if (tags.size() > agentProperties.getMaxTagCount()) {
            throw new IllegalArgumentException("标签数量超过 " + agentProperties.getMaxTagCount() + " 个上限（当前 "
                    + tags.size() + " 个），请精简后重新输出");
        }
        // 越池校验：逐个与标签池精确比对（池唯一数据源在 content，AI 不新造标签）
        for (String tag : tags) {
            if (!existsInTagPool(tag)) {
                outOfPool.add(tag);
            }
        }
        if (!outOfPool.isEmpty()) {
            throw new IllegalArgumentException("以下标签不在站内标签池中，禁止新造标签：" + String.join("、", outOfPool)
                    + "。只能使用标签池已有标签，请重新输出");
        }
        ValidatedSuggestion result = new ValidatedSuggestion();
        result.setTags(tags);
        return result;
    }

    /**
     * 推荐理由校验（板块五 5.2）：解析 JSON 取 reasons 数组，逐条校验序号范围与一句话字数上限
     *
     * <p>允许模型只给部分候选理由（缺条目不判失败，调用方对缺失候选置空理由）。</p>
     *
     * @param rawText        模型原始输出
     * @param candidateCount 候选篇数（index 合法范围 1..candidateCount）
     * @param maxChars       一句话理由字数上限
     * @return 校验通过的理由条目列表
     * @throws IllegalArgumentException 解析失败或校验不过（异常消息即回喂给模型的具体错误）
     */
    public List<ReasonItem> validateReasons(String rawText, int candidateCount, int maxChars) {
        JsonNode node = parseJson(rawText);
        JsonNode reasonsNode = node.path("reasons");
        if (!reasonsNode.isArray() || reasonsNode.isEmpty()) {
            throw new IllegalArgumentException("缺少 reasons 数组字段或数组为空");
        }
        List<ReasonItem> items = new ArrayList<>();
        for (JsonNode itemNode : reasonsNode) {
            // 序号合法性：必须在候选范围内（防模型幻觉出界序号错配卡片）
            int index = itemNode.path("index").asInt(-1);
            if (index < 1 || index > candidateCount) {
                throw new IllegalArgumentException("reasons 中 index=" + index
                        + " 超出候选范围（1-" + candidateCount + "），请修正后重新输出");
            }
            // 理由合法性：非空且不超一句话字数上限
            String reason = itemNode.path("reason").asText(null);
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("reasons 第 " + index + " 条缺少 reason 字段或内容为空");
            }
            if (reason.length() > maxChars) {
                throw new IllegalArgumentException("第 " + index + " 条理由超出 " + maxChars
                        + " 字上限（当前 " + reason.length() + " 字），请压缩后重新输出");
            }
            items.add(new ReasonItem(index, reason));
        }
        return items;
    }

    /**
     * 问答校验：非 JSON 自由文本，非空即可（结构化回答口径随板块四 RAG 转正收紧）
     */
    private ValidatedSuggestion validateAnswer(String rawText) {
        // QA 问答为自由文本（不要求 JSON），剥围栏后直接取全文
        String answer = stripCodeFence(rawText).trim();
        if (answer.isEmpty()) {
            throw new IllegalArgumentException("回答内容为空");
        }
        ValidatedSuggestion result = new ValidatedSuggestion();
        result.setAnswer(answer);
        return result;
    }

    /**
     * 单文本字段校验（POLISH/STRUCTURE 通用）：解析 JSON 取指定字段，非空即可
     *
     * @param rawText    模型原始输出
     * @param fieldName  JSON 字段名（polished/analysis）
     * @param fieldLabel 字段中文名（校验错误提示用）
     * @return 校验通过的建议内容
     */
    private ValidatedSuggestion validateTextField(String rawText, String fieldName, String fieldLabel) {
        JsonNode node = parseJson(rawText);
        String value = node.path(fieldName).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少 " + fieldName + " 字段或" + fieldLabel + "为空");
        }
        ValidatedSuggestion result = new ValidatedSuggestion();
        if ("polished".equals(fieldName)) {
            result.setPolished(value);
        } else {
            result.setAnalysis(value);
        }
        return result;
    }

    /**
     * 标签池精确比对（前缀搜索接口返回后按名称精确匹配；服务异常按越池处理防新造标签漏放）
     */
    private boolean existsInTagPool(String tag) {
        try {
            Result<List<TagSuggestDTO>> resp = contentClient.getTagSuggestions(tag);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                // 标签服务不可用时按「无法确认」处理：判越池拒绝（防新造标签漏放），回喂重试由调用方处理
                log.warn("标签越池校验时标签服务不可用 tagChars={}", tag.length());
                return false;
            }
            return resp.getData().stream().anyMatch(dto -> tag.equals(dto.getName()));
        } catch (Exception e) {
            log.warn("标签越池校验异常 tagChars={}", tag.length(), e);
            return false;
        }
    }

    /**
     * 解析模型输出为 JSON（容忍 ```json 围栏包裹；解析失败抛具体错误）
     */
    private JsonNode parseJson(String rawText) {
        String candidate = stripCodeFence(rawText).trim();
        // 截取首个 { 到最后一个 } 之间的内容（容忍模型在 JSON 前后夹带说明文字）
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
     * 剥离 Markdown 代码围栏（```json ... ```），无围栏原样返回
     */
    private String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak > 0) {
                trimmed = trimmed.substring(firstLineBreak + 1);
            }
            int closingFence = trimmed.lastIndexOf("```");
            if (closingFence >= 0) {
                trimmed = trimmed.substring(0, closingFence);
            }
        }
        return trimmed;
    }
}
