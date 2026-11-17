package com.litisle.litisleai.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.constant.AiContentConstant;
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
 * <p>【通俗说明】AI 模型返回的内容不能直接用——它可能忘了带字段、字数超标、
 * 甚至自作主张造新标签。本类就是"质检员"：拿到模型的原始输出后，按用途选规则
 * 逐项检查（摘要够不够短、标签是否都在站内标签池里、回答是否为空等）。检查全部
 * 通过就返回装好字段的结果对象；任何一项不过就抛异常，并把具体错误写成一句话
 * （比如"摘要超出 300 字上限，请压缩后重新输出"）——这句话会被上层原样回喂给
 * 模型，让它自己改了再交。这样质检不靠模型"自觉"，而是程序说了算。</p>
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
     *
     * <p>【通俗说明】质检合格的"合格证"：模型输出通过校验后，把内容装进对应的
     * 字段里（摘要装 summary、标签装 tags……），一次校验只会填一个用途的字段，
     * 其余留空。上层服务只从这里拿干净可用的内容，不再接触模型原始输出。</p>
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
        /** 改写候选版本列表（板块十五 15.1，全部按粒度校验通过） */
        private List<com.litisle.litisleai.dto.RewriteCandidate> rewriteCandidates;
        /** 优化建议清单（板块十五 15.1：冗余/歧义/语气/结构诊断） */
        private List<String> rewriteSuggestions;
        /** 文章草稿正文（板块十五 15.2） */
        private String draft;
        /** 段落结构建议（板块十五 15.2） */
        private String outline;
    }

    /**
     * 推荐理由条目载体（板块五 5.2：index 对应候选序号 1 起，reason 为一句话理由）
     *
     * <p>【通俗说明】一条推荐理由：第几个候选文章（index 从 1 数起）+ 为什么推荐它
     * 的一句话理由（reason），上层按序号把理由配到对应的推荐卡片上。</p>
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
     *
     * <p>【通俗说明】质检入口：先看模型有没有交白卷（空输出直接判失败），再按用途
     * 分派给对应的专项检查——摘要查 summary、打标签查 tags、问答查回答全文、
     * 润色和结构分析查各自的文本字段。用不上的用途直接拒绝，防止漏检。</p>
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
     *
     * <p>【通俗说明】三查：能解析出 JSON 吗 → summary 字段有内容吗 → 字数没超上限吗？
     * 超了就把"上限多少、当前多少"都写进错误信息，模型一看就知道要压到多短。</p>
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
     *
     * <p>【通俗说明】三查：tags 是个数组吗 → 数量没超上限吗 → 每个标签真的存在于
     * 站内标签池吗？第三步最关键：AI 最爱"发明"站里根本没有的标签，所以每个标签
     * 都要拿去和 content 服务的标签池逐一比对，发现一个不存在的就把违规标签列出来
     * 报错，逼模型只能用站内已有的标签。</p>
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
     *
     * <p>【通俗说明】逐条检查推荐理由：序号必须在候选范围内（防模型编出第 8 条
     * 理由却只有 5 篇候选），理由必须非空且不超一句话字数。模型偷懒只给了部分
     * 候选的理由没关系——缺的条目不判失败，上层把对应卡片理由留空即可。</p>
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
     *
     * <p>【通俗说明】问答不要求模型输出 JSON——它本来就是给人读的自然语言回答。
     * 所以只做一件事：剥掉可能的代码围栏后取全文，只要不为空就算通过。</p>
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
     *
     * <p>【通俗说明】润色建议和结构分析共用同一套检查：解析 JSON、取指定字段、
     * 非空即通过——规则完全一样，只是字段名不同，所以抽成一个通用方法。</p>
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
     *
     * <p>【通俗说明】拿着一个标签去问 content 服务："站里真有这个标签吗？"
     * 注意两个细节：① 前缀搜索接口返回的候选要做名称完全相等比对，防止
     * "Java" 蒙混过 "JavaScript"；② 标签服务挂了或没返回数据时按"查无此标签"
     * 处理——宁可让模型重试，也不能放过一个可能凭空捏造的标签。</p>
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
     * 多级改写建议校验（板块十五 15.1）：解析 {"candidates":[{"version","reason"}],
     * "suggestions":[...]} 结构，按目标粒度约束——TITLE 字数上限（对齐 content 侧标题约束）、
     * WORD 防整句改写/近义词堆砌（「词语不得偏离原意」的机械代理约束，主约束在 prompt 硬约束）；
     * 优化建议清单允许缺省（模型只给候选时置 null）
     *
     * @param rawText        模型原始输出
     * @param targetType     目标粒度（TITLE/PARAGRAPH/SENTENCE/WORD）
     * @param candidateLimit 候选数量上限（配置外置）
     * @param reasonMaxChars 改动理由一句话字数上限
     * @param titleMaxChars  TITLE 粒度候选字数上限
     * @param originalText   改写原文（WORD 粒度长度比对基准）
     * @return 校验通过的改写建议
     * @throws IllegalArgumentException 解析失败或校验不过（异常消息即回喂给模型的具体错误）
     *
     * <p>【通俗说明】改写场景的质检，每个候选版本（version + 改动理由 reason）
     * 按改写粒度加查：改标题查字数上限（标题太长文章页放不下）；改词语额外查
     * 长度——候选超过原文 2 倍基本就是模型把"改一个词"干成了"改写整句"，退回重做；
     * 改动理由必须非空且不超一句话。另外候选总数量不能超上限，防模型输出膨胀。
     * 附加的优化建议清单是可选的，给了就收，没给不扣分。</p>
     */
    public ValidatedSuggestion validateRewrite(String rawText, String targetType, int candidateLimit,
                                               int reasonMaxChars, int titleMaxChars, String originalText) {
        JsonNode node = parseJson(rawText);
        JsonNode candidatesNode = node.path("candidates");
        if (!candidatesNode.isArray() || candidatesNode.isEmpty()) {
            throw new IllegalArgumentException("缺少 candidates 数组字段或数组为空");
        }
        List<com.litisle.litisleai.dto.RewriteCandidate> candidates = new ArrayList<>();
        for (JsonNode itemNode : candidatesNode) {
            String version = itemNode.path("version").asText(null);
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("candidates 中缺少 version 字段或改写版本为空");
            }
            // TITLE 粒度字数上限：越限候选作者无法采用（content 侧同上限），回喂压缩
            if (AiContentConstant.TARGET_TYPE_TITLE.equals(targetType) && version.length() > titleMaxChars) {
                throw new IllegalArgumentException("标题候选超出 " + titleMaxChars + " 字上限（当前 "
                        + version.length() + " 字），请压缩后重新输出");
            }
            // WORD 粒度防整句改写/堆砌：候选长度超过原文 2 倍即判偏离原意（机械代理兜底约束）
            if (AiContentConstant.TARGET_TYPE_WORD.equals(targetType)) {
                int baseLen = originalText == null ? 0 : originalText.trim().length();
                int wordMaxChars = Math.max(baseLen * 2, 10);
                if (version.length() > wordMaxChars) {
                    throw new IllegalArgumentException("词语粒度改写明显超出原文长度（原文 " + baseLen
                            + " 字，候选 " + version.length() + " 字），疑似整句改写或近义词堆砌，"
                            + "请贴近原文语义重新输出");
                }
            }
            String reason = itemNode.path("reason").asText(null);
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("candidates 中缺少 reason 字段或改动理由为空");
            }
            if (reason.length() > reasonMaxChars) {
                throw new IllegalArgumentException("改动理由超出 " + reasonMaxChars + " 字上限（当前 "
                        + reason.length() + " 字），请压缩后重新输出");
            }
            candidates.add(new com.litisle.litisleai.dto.RewriteCandidate(version, reason));
        }
        // 候选数量上限：防超长输出（数量外置可调）
        if (candidates.size() > candidateLimit) {
            throw new IllegalArgumentException("候选版本数量超过 " + candidateLimit + " 个上限（当前 "
                    + candidates.size() + " 个），请精简后重新输出");
        }
        // 优化建议清单：允许缺省，给则取非空字符串条目（缺条目不判失败，与推荐理由校验同口径）
        JsonNode suggestionsNode = node.path("suggestions");
        List<String> suggestions = new ArrayList<>();
        if (suggestionsNode.isArray()) {
            for (JsonNode suggestionNode : suggestionsNode) {
                String text = suggestionNode.asText(null);
                if (text != null && !text.isBlank()) {
                    suggestions.add(text);
                }
            }
        }
        ValidatedSuggestion result = new ValidatedSuggestion();
        result.setRewriteCandidates(candidates);
        result.setRewriteSuggestions(suggestions.isEmpty() ? null : suggestions);
        return result;
    }

    /**
     * 文章草稿校验（板块十五 15.2）：解析 {"draft":"草稿正文","outline":"段落结构建议"}，
     * 草稿字数上限校验（防成本放大，超出回喂压缩），段落结构建议非空
     *
     * @param rawText      模型原始输出
     * @param draftMaxChars 草稿字数上限（配置外置，默认 2000）
     * @return 校验通过的草稿建议
     * @throws IllegalArgumentException 解析失败或校验不过（异常消息即回喂给模型的具体错误）
     *
     * <p>【通俗说明】AI 帮作者起草文章时的质检：两样东西都要有——草稿正文
     * （draft，查非空和字数上限，防模型写出一篇论文把成本翻倍）和段落结构
     * 建议（outline，告诉作者每段写什么，缺了不行）。</p>
     */
    public ValidatedSuggestion validateDraft(String rawText, int draftMaxChars) {
        JsonNode node = parseJson(rawText);
        String draft = node.path("draft").asText(null);
        if (draft == null || draft.isBlank()) {
            throw new IllegalArgumentException("缺少 draft 字段或草稿为空");
        }
        if (draft.length() > draftMaxChars) {
            throw new IllegalArgumentException("草稿超出 " + draftMaxChars + " 字上限（当前 "
                    + draft.length() + " 字），请压缩后重新输出");
        }
        String outline = node.path("outline").asText(null);
        if (outline == null || outline.isBlank()) {
            throw new IllegalArgumentException("缺少 outline 字段或段落结构建议为空");
        }
        ValidatedSuggestion result = new ValidatedSuggestion();
        result.setDraft(draft);
        result.setOutline(outline);
        return result;
    }

    /**
     * 解析模型输出为 JSON（容忍 ```json 围栏包裹；解析失败抛具体错误）
     *
     * <p>【通俗说明】先把代码围栏剥掉，再"掐头去尾"——只取第一个 { 到最后一个 }
     * 之间的内容（模型经常在 JSON 前后絮叨几句"好的，以下是结果"之类的话，
     * 这样能直接把废话扔掉）；取出来的东西解析不了就报错，让模型重新输出。</p>
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
     *
     * <p>【通俗说明】模型输出 JSON 时总爱套一层 ```json ... ``` 的"包装盒"，
     * 本方法负责拆盒：开头以 ``` 开头就跳过第一行（那行写着 json），再把结尾
     * 的 ``` 去掉；没有包装盒就原样返回。</p>
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
