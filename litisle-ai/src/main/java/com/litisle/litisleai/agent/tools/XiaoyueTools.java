package com.litisle.litisleai.agent.tools;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.agent.AgentCallContext;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.constant.AiContentConstant;
import com.litisle.litisleai.service.RagSearchService;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.dto.content.TagSuggestDTO;
import com.litisle.litisleapi.dto.search.SimilarConditionRequestDTO;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litisleapi.feign.SearchClient;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 小悦能力工具集（板块二 2.2 Function Calling：AiServices 按注解自动注册给 Agent；
 * 板块四 4.2：工具④知识库检索由占位转正；板块九：新增结果动作与读后服务四工具；
 * 板块十一：新增多文对比与收藏夹语义检索两工具；板块十二：新增找系列/热榜上下文/风格仿写素材三工具）
 *
 * <p>【铁律】工具只读、不写业务表（唯一例外：工具⑥收藏动作为用户显式指令驱动的
 * 业务写操作，经 content 收藏域契约执行，幂等语义由提供方收口）；工具失败不抛异常
 * 中断会话，而是返回失败观察文本，由模型自行调整（回喂不中断口径）；出入参日志
 * 不带正文全文（只记长度与标识）。</p>
 *
 * <p>【占位口径】敏感词预检（复用 DFA 闸门）暂无内部端点，占位实现返回「工具暂不可用」，
 * 模型改走无预检回答，闸门兜底仍由调用方执行；知识库检索（rag.enabled=true）已转正为
 * RAG 检索工具（topK+阈值+引用回查），rag.enabled=false 或 RAG 不可用时返回占位说明
 * （Q54 降级：模型改走无检索回答并标注「未引用站内检索结果」）。</p>
 *
 * <p>【数据源】稿件与标签池唯一数据源在 content 服务，经 ContentClient 回查（fail-fast
 * 降级工厂已就绪）；工具结果回喂文本出站时统一经过脱敏装饰器（P1 出站红线）。</p>
 *
 * <p>【板块九口径】序号解析（工具⑤）与结果动作（工具⑥）依赖会话结果集注册
 * （SessionResultRegistryService，1.1 公共地基）：跨会话不可引用，结果集过期/越界
 * 一律明确提示重新发起检索，不猜不编造；读后切片检索（工具⑦⑧）只在该篇文章自身
 * 切片范围内检索，可见性回查铁律照搬（非已发布/非公开即剔除）。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XiaoyueTools {

    /** RAG 检索降级文案（rag.enabled=false 或无有效命中：模型改走无检索回答，Q54） */
    private static final String RAG_DEGRADED_HINT =
            "知识库检索暂不可用或无相关资料：请基于稿件内容直接回答，并在回答开头注明「未引用站内检索结果」。";

    /** 序号分隔正则（兼容「2、3」「2,3」「2 3」等表达） */
    private static final String SEQ_SEPARATOR_REGEX = "[、,，\\s]+";

    /** 多文对比一次篇数上限（板块十一 3.1：超限明确告知不静默截断——token 随篇数线性涨由上限管控） */
    private static final int COMPARE_MAX_ARTICLES = 3;

    /** 多文对比单篇摘要级内容截断字符数（对比只取摘要级素材，全文对比 token 放大无必要） */
    private static final int COMPARE_ARTICLE_MAX_CHARS = 2000;

    /** 收藏夹检索白名单取数上限（板块十一 3.2：防 includeArticleIds terms filter 过大） */
    private static final int FAVORITE_FETCH_LIMIT = 100;

    /** 内容服务客户端（取稿件/查标签池/收藏动作/收藏列表） */
    private final ContentClient contentClient;

    /** 搜索服务客户端（板块十一 3.2：条件版语义检索承载收藏白名单 filter） */
    private final SearchClient searchClient;

    /** RAG 检索服务（板块四 4.2 转正；rag.enabled=false 时 Bean 缺席，工具降级） */
    private final org.springframework.beans.factory.ObjectProvider<com.litisle.litisleai.service.RagSearchService> ragSearchProvider;

    /** 会话结果集注册服务（板块九 1.1 公共地基：序号→articleId 映射） */
    private final com.litisle.litisleai.service.SessionResultRegistryService resultRegistry;

    /** Agent 配置（结果动作批量上限来源） */
    private final AiAgentProperties agentProperties;


    /**
     * 工具①：取当前稿件标题与正文（经 ContentClient 回查，供摘要/标签/问答任务）
     *
     * @param articleId 文章 ID
     * @return 标题+正文文本；失败返回失败观察文本（不中断会话）
     */
    @Tool("获取指定文章的标题与正文内容，用于摘要生成、标签推荐、内容问答等任务")
    public String getArticleDraft(Long articleId) {
        try {
            // 文章按 bizType=1 回查；Result.fail 表示稿件不存在
            Result<ContentDTO> resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, articleId);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                log.warn("工具①取稿件失败：稿件不存在或返回异常 articleId={}", articleId);
                return "取稿失败：文章不存在或暂时无法获取，请基于已有信息继续任务。";
            }
            ContentDTO content = resp.getData();
            // 日志纪律：只记长度，不打印正文全文
            log.info("工具①取稿件成功 articleId={} titleChars={} bodyChars={} visibility={}",
                    articleId,
                    content.getTitle() == null ? 0 : content.getTitle().length(),
                    content.getBody() == null ? 0 : content.getBody().length(),
                    content.getVisibility());
            return "文章标题：" + content.getTitle() + "\n文章正文：\n" + content.getBody();
        } catch (Exception e) {
            // 工具失败作为观察结果回喂模型自行调整，不直接中断会话
            log.warn("工具①取稿件异常 articleId={}", articleId, e);
            return "取稿失败：内容服务暂时不可用，请基于已有信息继续任务。";
        }
    }

    /**
     * 工具②：按关键词查标签池候选（经 ContentClient，标签池唯一数据源在 content）
     *
     * @param keyword 关键词前缀
     * @return 候选标签名列表文本；失败返回失败观察文本
     */
    @Tool("按关键词查询站内标签池候选标签，推荐标签前必须先查询确认标签存在")
    public String getTagPool(String keyword) {
        try {
            Result<List<TagSuggestDTO>> resp = contentClient.getTagSuggestions(keyword);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                log.warn("工具②查标签池失败 keywordChars={}", keyword == null ? 0 : keyword.length());
                return "查标签池失败：标签服务暂时不可用，请基于常识给出候选并提醒作者自行核对。";
            }
            List<String> names = resp.getData().stream()
                    .map(TagSuggestDTO::getName)
                    .collect(Collectors.toList());
            log.info("工具②查标签池成功 keywordChars={} candidates={}", keyword.length(), names.size());
            // 空候选也明确告知，模型据此不虚构标签
            if (names.isEmpty()) {
                return "标签池无「" + keyword + "」相关候选标签，请勿新造标签。";
            }
            return "标签池候选：" + String.join("、", names);
        } catch (Exception e) {
            // 工具失败作为观察结果回喂，不中断会话
            log.warn("工具②查标签池异常 keywordChars={}", keyword == null ? 0 : keyword.length(), e);
            return "查标签池失败：标签服务暂时不可用，请基于常识给出候选并提醒作者自行核对。";
        }
    }

    /**
     * 工具③：敏感词预检（占位：复用既有 DFA 闸门需内部端点，未就绪前返回占位观察文本；
     * 闸门兜底仍由调用方 content/review 在展示前执行）
     *
     * @param text 待预检文本
     * @return 占位说明文本（模型据此改走无预检回答）
     */
    @Tool("对文本做敏感词预检（当前暂不可用，调用会返回占位说明）")
    public String sensitiveWordPrecheck(String text) {
        // todo 占位实现：DFA 闸门内部端点未落地（2.2 条目口径），注册占位保持工具面稳定
        log.info("工具③敏感词预检（占位）textChars={}", text == null ? 0 : text.length());
        return "敏感词预检工具暂不可用：请直接产出建议内容，展示前的敏感词闸门由调用方兜底执行。";
    }

    /**
     * 工具④：站内知识库检索（板块四 4.2 由占位转正：RAG 向量检索 topK+阈值+引用回查；
     * 命中引用写入 RagContext 供终答回传引用卡片；RAG 不可用降级为占位说明 Q54）
     *
     * @param query 检索问题
     * @return 知识片段文本（含引用溯源与使用规则）或降级占位说明
     */
    @Tool("在站内知识库中检索与问题相关的公开文章片段，回答站内问题前应先检索")
    public String searchKnowledgeBase(String query) {
        // rag.enabled=false：Bean 缺席，降级占位（模型改走无检索回答并标注）
        RagSearchService ragSearchService = ragSearchProvider.getIfAvailable();
        if (ragSearchService == null) {
            log.info("工具④知识库检索（RAG 未启用，降级）queryChars={}", query == null ? 0 : query.length());
            return RAG_DEGRADED_HINT;
        }
        try {
            // RAG 检索：命中引用已在 service 内写入 RagContext（chat 编排终答 drain 回传）
            String prompt = ragSearchService.searchForPrompt(query);
            if (prompt == null) {
                // 无有效命中/回查全部移除：降级占位（事实性结论只答暂无资料）
                return RAG_DEGRADED_HINT;
            }
            return prompt;
        } catch (Exception e) {
            // 工具失败作为观察结果回喂模型自行调整，不直接中断会话
            log.warn("工具④知识库检索异常 queryChars={}", query == null ? 0 : query.length(), e);
            return RAG_DEGRADED_HINT;
        }
    }

    /**
     * 工具⑤：会话结果集序号解析（板块九 1.1 公共地基消费端：序号 → articleId + 标题）
     *
     * <p>【失败语义】一次性请求不可用 / 结果集过期或未记录 / 序号越界 / 文章已下架不可见
     * 均返回明确观察文本，提示重新发起检索——不猜不编造（1.1 铁律）。</p>
     *
     * @param seq 结果集序号（从 1 起，对应最近一次推荐/检索结果的展示顺序）
     * @return 序号对应文章定位文本；失败返回明确原因观察文本（不中断会话）
     */
    @Tool("解析当前会话最近一次推荐/检索结果集的序号，返回对应文章 ID 与标题；引用「第 N 篇」前必须先调用本工具定位")
    public String resolveResultArticle(Integer seq) {
        // 会话定位：结果集按会话隔离，一次性请求（无会话）即不可用
        AgentCallContext context = AgentCallContext.get();
        Long sessionId = context == null ? null : context.getSessionId();
        if (sessionId == null) {
            return "该操作需要在会话中使用：请先发起检索或推荐获得结果列表，再引用序号。";
        }
        // 序号解析：过期/未记录/越界统一 empty，明确提示重新发起检索
        int size = resultRegistry.size(sessionId);
        java.util.Optional<Long> articleId = resultRegistry.resolve(sessionId, seq == null ? -1 : seq);
        if (articleId.isEmpty()) {
            return size <= 0
                    ? "当前会话没有可用的结果集（可能已过期），请重新发起检索或推荐后再引用序号。"
                    : "序号 " + seq + " 超出当前结果集范围（共 " + size + " 篇），请确认序号后重试。";
        }
        // 可见性回查：记录时可见不代表现在可见（下架即失效），非已发布/非公开明确响应
        Long resolvedId = articleId.get();
        try {
            Result<ContentDTO> resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, resolvedId);
            ContentDTO content = resp == null || !resp.isSuccess() ? null : resp.getData();
            if (content == null
                    || content.getStatus() == null || content.getStatus() != AiContentConstant.STATUS_PUBLISHED
                    || content.getVisibility() == null || content.getVisibility() != AiContentConstant.VISIBILITY_PUBLIC) {
                log.info("结果集序号文章已不可见 seq={} articleId={}", seq, resolvedId);
                return "第 " + seq + " 篇文章已下架或不可见，无法继续操作，请重新发起检索。";
            }
            return "第 " + seq + " 篇对应文章《" + content.getTitle() + "》（articleId=" + resolvedId + "）。";
        } catch (Exception e) {
            log.warn("结果集序号文章回查异常 seq={} articleId={}", seq, resolvedId, e);
            return "第 " + seq + " 篇文章状态暂时无法确认，请稍后重试或重新发起检索。";
        }
    }

    /**
     * 工具⑥：收藏结果集文章动作（板块九 1.2 结果动作：0 次生成成本，纯契约调用）
     *
     * <p>【业务逻辑步骤】1. 会话/登录态前置校验（一次性请求或未登录明确拒绝）；
     * 2. 序号清单解析与批量上限校验；3. 逐个序号解析 articleId（过期/越界记为该条失败）；
     * 4. 逐篇调 content 收藏契约（targetType=1 文章，幂等语义由提供方收口）；
     * 5. 逐条组装成功/失败观察文本——动作失败明确响应，不静默吞掉。</p>
     *
     * <p>【计量口径】0 次生成成本：意图路由后纯契约调用，不落 AI 计量明细
     * （Agent 意图路由轮按入口 purpose 照常计量）。</p>
     *
     * @param seqNumbers 要收藏的结果集序号清单（如「2、3」；一次最多批量上限个）
     * @return 逐条收藏结果观察文本（成功/失败均明确列出，不中断会话）
     */
    @Tool("收藏当前会话结果集中的文章：传入要收藏的序号清单（如 2、3），逐篇收藏并返回每条结果")
    public String favoriteArticles(String seqNumbers) {
        // 登录态前置：收藏归属当前登录用户，未登录明确拒绝（不静默）
        AgentCallContext context = AgentCallContext.get();
        Long userId = context == null ? null : context.getUserId();
        Long sessionId = context == null ? null : context.getSessionId();
        if (userId == null) {
            return "收藏功能需要登录后使用。";
        }
        if (sessionId == null) {
            return "收藏结果集文章需要在会话中使用：请先发起检索或推荐获得结果列表。";
        }
        // 序号清单解析：空参明确提示；含非数字 token 返回 null 由调用方给出解析失败提示
        if (seqNumbers == null || seqNumbers.isBlank()) {
            return "请提供要收藏的结果集序号（如「收藏第 2、3 篇」请传 2、3）。";
        }
        List<Integer> seqs = parseSeqNumbers(seqNumbers);
        if (seqs == null) {
            return "序号清单「" + seqNumbers + "」无法解析，请只传入数字序号（如 2、3）。";
        }
        if (seqs.isEmpty()) {
            return "请提供要收藏的结果集序号（如「收藏第 2、3 篇」请传 2、3）。";
        }
        // 批量上限：防批量写放大，超出部分明确说明未处理
        int maxBatch = agentProperties.getResultActionMaxBatch();
        List<Integer> truncated = null;
        if (seqs.size() > maxBatch) {
            truncated = seqs.subList(maxBatch, seqs.size());
            seqs = new ArrayList<>(seqs.subList(0, maxBatch));
        }
        int size = resultRegistry.size(sessionId);
        StringBuilder observation = new StringBuilder("收藏结果：\n");
        for (Integer seq : seqs) {
            // 逐个解析序号：过期/越界记为该条失败，不影响其余条目继续执行
            java.util.Optional<Long> articleId = resultRegistry.resolve(sessionId, seq);
            if (articleId.isEmpty()) {
                observation.append("第 ").append(seq).append(" 篇：失败（")
                        .append(size <= 0 ? "结果集已过期，请重新发起检索" : "序号超出结果集范围（共 " + size + " 篇）")
                        .append("）\n");
                continue;
            }
            try {
                // 收藏契约调用（targetType=1 文章）：提供方收口类型白名单/可见性/幂等语义
                Result<Void> resp = contentClient.favorite(userId, AiContentConstant.FAVORITE_TARGET_ARTICLE, articleId.get());
                if (resp != null && resp.isSuccess()) {
                    observation.append("第 ").append(seq).append(" 篇（articleId=").append(articleId.get())
                            .append("）：收藏成功\n");
                } else {
                    // 动作失败明确响应不静默吞掉（提供方 Result.fail：目标不可见/不存在等）
                    observation.append("第 ").append(seq).append(" 篇（articleId=").append(articleId.get())
                            .append("）：收藏失败（文章不可见或已下架）\n");
                }
            } catch (Exception e) {
                log.warn("收藏动作调用异常 userId={} seq={} articleId={}", userId, seq, articleId.get(), e);
                observation.append("第 ").append(seq).append(" 篇（articleId=").append(articleId.get())
                        .append("）：收藏失败（内容服务暂时不可用）\n");
            }
        }
        if (truncated != null) {
            observation.append("另有 ").append(truncated.size())
                    .append(" 个序号超出单次批量上限（").append(maxBatch).append("），未处理，请分批收藏。");
        }
        return observation.toString();
    }

    /**
     * 工具⑦：单文切片语义检索（板块九 1.4 读后追问底座：只在该篇文章自身切片内检索）
     *
     * <p>【口径】与全库知识库检索工具分开——本工具经 articleId filter 收窄范围，
     * 引用回查铁律照搬（非已发布/非公开/contentHash 不一致即剔除或标注）；
     * 命中引用写入 RagContext 供终答回传引用卡片。</p>
     *
     * @param articleId 目标文章 ID
     * @param query     用户问题（在该篇切片范围内语义检索）
     * @return 该篇切片片段文本（含使用规则）或降级占位说明
     */
    @Tool("在指定文章自身的切片范围内检索与问题相关的内容（读后追问/术语解释用，不检索其他文章）")
    public String searchArticlePassages(Long articleId, String query) {
        // RAG 未启用：Bean 缺席，降级占位（模型改走基于稿件内容的回答并标注）
        com.litisle.litisleai.service.RagSearchService ragSearchService = ragSearchProvider.getIfAvailable();
        if (ragSearchService == null) {
            log.info("工具⑦单文切片检索（RAG 未启用，降级）articleId={} queryChars={}",
                    articleId, query == null ? 0 : query.length());
            return RAG_DEGRADED_HINT;
        }
        try {
            String prompt = ragSearchService.retrieveArticlePassages(articleId, query);
            if (prompt == null) {
                // 无有效命中/回查全部移除：降级占位（事实性结论只答暂无资料）
                return RAG_DEGRADED_HINT;
            }
            return prompt;
        } catch (Exception e) {
            // 工具失败作为观察结果回喂模型自行调整，不直接中断会话
            log.warn("工具⑦单文切片检索异常 articleId={} queryChars={}", articleId,
                    query == null ? 0 : query.length(), e);
            return RAG_DEGRADED_HINT;
        }
    }

    /**
     * 工具⑧：按序取单文全部切片（板块九 1.5 大纲导航 / 1.8 金句摘录底座：
     * chunkIndex 升序 = 原文段落顺序，逐片过可见性回查）
     *
     * @param articleId 目标文章 ID
     * @return 按序编号的切片文本（大纲拼装/金句抽取素材）；不可用返回明确观察文本
     */
    @Tool("按原文顺序获取指定文章的全部切片内容（列提纲/抽取原文金句用）")
    public String getArticleSequentialChunks(Long articleId) {
        // RAG 未启用：Bean 缺席，降级占位
        com.litisle.litisleai.service.RagSearchService ragSearchService = ragSearchProvider.getIfAvailable();
        if (ragSearchService == null) {
            log.info("工具⑧单文全切片获取（RAG 未启用，降级）articleId={}", articleId);
            return RAG_DEGRADED_HINT;
        }
        try {
            List<String> texts = ragSearchService.listArticleChunkTexts(articleId);
            if (texts.isEmpty()) {
                // 文章不可见/无切片/回查全移除：明确响应不编造
                return "该文章暂无可用的切片内容（可能已下架或尚未建索引），无法继续任务。";
            }
            StringBuilder material = new StringBuilder("文章切片（按原文顺序）：\n");
            for (int i = 0; i < texts.size(); i++) {
                material.append("【片段").append(i + 1).append("】\n").append(texts.get(i)).append("\n\n");
            }
            return material.toString().trim();
        } catch (Exception e) {
            // 工具失败作为观察结果回喂模型自行调整，不直接中断会话
            log.warn("工具⑧单文全切片获取异常 articleId={}", articleId, e);
            return "文章切片获取失败：内容服务暂时不可用，请基于已有信息继续任务。";
        }
    }

    /**
     * 序号清单解析（结果动作/多文对比共用：按分隔符拆分取数字）
     *
     * @param seqNumbers 序号清单文本（如「2、3」）
     * @return 序号列表；清单为空返回空列表；含非数字 token 返回 null（调用方给解析失败提示）
     */
    private List<Integer> parseSeqNumbers(String seqNumbers) {
        List<Integer> seqs = new ArrayList<>();
        for (String part : seqNumbers.split(SEQ_SEPARATOR_REGEX)) {
            if (part.isBlank()) {
                continue;
            }
            try {
                seqs.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return seqs;
    }

    /**
     * 工具⑨：多文对比素材组装（板块十一 3.1：序号定位 ≤3 篇 → 各取摘要级内容，
     * 对比结论由模型在本轮生成——一次对比生成，token 随篇数线性涨由篇数上限管控）
     *
     * <p>【口径】超 3 篇明确告知上限不静默截断；序号过期/越界/文章不可见逐条明确列出；
     * 单篇正文截断为摘要级素材（截断处标注「……（已截断）」防模型误当全文）。</p>
     *
     * @param seqNumbers 要对比的结果集序号清单（如「1、2、3」；一次最多 3 篇）
     * @return 各篇摘要级素材观察文本（含序号与标题）；失败逐条明确原因，不中断会话
     */
    @Tool("对比当前会话结果集中的多篇文章：传入要对比的序号清单（如 1、2、3，一次最多 3 篇），返回各篇摘要级内容供对比分析")
    public String compareResultArticles(String seqNumbers) {
        // 会话定位：结果集按会话隔离，一次性请求（无会话）即不可用
        AgentCallContext context = AgentCallContext.get();
        Long sessionId = context == null ? null : context.getSessionId();
        if (sessionId == null) {
            return "多文对比需要在会话中使用：请先发起检索或推荐获得结果列表，再引用序号对比。";
        }
        // 序号清单解析：空参/含非数字明确提示，不猜不编造
        if (seqNumbers == null || seqNumbers.isBlank()) {
            return "请提供要对比的结果集序号（如「对比第 1、2 篇」请传 1、2）。";
        }
        List<Integer> seqs = parseSeqNumbers(seqNumbers);
        if (seqs == null) {
            return "序号清单「" + seqNumbers + "」无法解析，请只传入数字序号（如 1、2）。";
        }
        if (seqs.isEmpty()) {
            return "请提供要对比的结果集序号（如「对比第 1、2 篇」请传 1、2）。";
        }
        // 篇数上限（板块十一 3.1：超限明确告知，不静默截断）
        if (seqs.size() > COMPARE_MAX_ARTICLES) {
            return "多文对比一次最多 " + COMPARE_MAX_ARTICLES + " 篇，当前传了 " + seqs.size()
                    + " 篇，请精简序号清单后重试。";
        }
        int size = resultRegistry.size(sessionId);
        StringBuilder material = new StringBuilder();
        int loaded = 0;
        for (Integer seq : seqs) {
            // 逐个解析序号：过期/越界记为该条失败，不影响其余篇目继续组装
            java.util.Optional<Long> articleId = resultRegistry.resolve(sessionId, seq);
            if (articleId.isEmpty()) {
                material.append("第 ").append(seq).append(" 篇：无法获取（")
                        .append(size <= 0 ? "结果集已过期，请重新发起检索" : "序号超出结果集范围（共 " + size + " 篇）")
                        .append("）\n");
                continue;
            }
            try {
                // 取稿回查（可见性双保险：结果集记录时可见不代表现在可见）
                Result<ContentDTO> resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, articleId.get());
                ContentDTO content = resp == null || !resp.isSuccess() ? null : resp.getData();
                if (content == null
                        || content.getStatus() == null || content.getStatus() != AiContentConstant.STATUS_PUBLISHED
                        || content.getVisibility() == null || content.getVisibility() != AiContentConstant.VISIBILITY_PUBLIC) {
                    material.append("第 ").append(seq).append(" 篇：无法获取（文章已下架或不可见）\n");
                    continue;
                }
                // 摘要级素材截断（截断处标注，防模型把截断文本误当全文）
                String body = content.getBody() == null ? "" : content.getBody();
                if (body.length() > COMPARE_ARTICLE_MAX_CHARS) {
                    body = body.substring(0, COMPARE_ARTICLE_MAX_CHARS) + "……（已截断）";
                }
                material.append("【第 ").append(seq).append(" 篇《").append(content.getTitle()).append("》】\n")
                        .append(body).append("\n\n");
                loaded++;
            } catch (Exception e) {
                log.warn("工具⑨多文对比取稿异常 seq={} articleId={}", seq, articleId.get(), e);
                material.append("第 ").append(seq).append(" 篇：无法获取（内容服务暂时不可用）\n");
            }
        }
        if (loaded == 0) {
            return material.isEmpty() ? "没有可对比的文章。" : material.toString().trim();
        }
        material.append("请基于以上各篇内容完成对比分析（仅基于素材内容，不编造未提供的细节）。");
        return material.toString().trim();
    }

    /**
     * 工具⑩：收藏夹语义检索（板块十一 3.2：拉取用户收藏文章 ID 白名单 → 条件版语义检索
     * includeArticleIds filter + queryText knn——只返回已收藏且当前公开的文章）
     *
     * <p>【口径】收藏为空/检索无命中/依赖不可用均明确响应；visibility=PUBLIC 铁律由 search
     * 侧强制（已下架文章不入索引，白名单命中即公开）；白名单上限 FAVORITE_FETCH_LIMIT 防
     * terms filter 过大。</p>
     *
     * @param queryText 检索主题（如「讲 RAG 的」；空串退化为纯白名单按热度取回，仍只出已收藏文章）
     * @return 收藏夹命中卡片观察文本；失败/为空返回明确原因，不中断会话
     */
    @Tool("在当前用户收藏的文章中检索：传入检索主题（如「讲 RAG 的」），只返回已收藏且公开的文章")
    public String searchMyFavorites(String queryText) {
        // 登录态前置：收藏归属当前登录用户，未登录明确拒绝（不静默）
        AgentCallContext context = AgentCallContext.get();
        Long userId = context == null ? null : context.getUserId();
        if (userId == null) {
            return "收藏夹检索需要登录后使用。";
        }
        try {
            // ① 拉取收藏白名单（按收藏时间倒序取前 N 篇文章收藏）
            Result<List<Long>> resp = contentClient.listFavoriteArticleIds(userId, FAVORITE_FETCH_LIMIT);
            List<Long> favoriteIds = resp == null || !resp.isSuccess() || resp.getData() == null
                    ? List.of() : resp.getData();
            if (favoriteIds.isEmpty()) {
                return "你还没有收藏文章，可先在文章页收藏后再使用收藏夹检索。";
            }
            // ② 白名单语义检索（includeArticleIds must filter 收窄到收藏集合；公开铁律由 search 强制）
            SimilarConditionRequestDTO dto = new SimilarConditionRequestDTO();
            dto.setRequestId(context == null ? null : context.getRequestId());
            dto.setQueryText(queryText);
            dto.setIncludeArticleIds(favoriteIds);
            Result<List<SimilarArticleVO>> searchResp = searchClient.similarByCondition(dto);
            List<SimilarArticleVO> hits = searchResp == null || !searchResp.isSuccess() || searchResp.getData() == null
                    ? List.of() : searchResp.getData();
            if (hits.isEmpty()) {
                return "收藏中未找到与「" + (queryText == null || queryText.isBlank() ? "该主题" : queryText)
                        + "」相关的文章。";
            }
            // ③ 组装命中卡片观察文本（标题/作者/相关度，供模型整理回答）
            StringBuilder observation = new StringBuilder("收藏夹命中（共 ").append(hits.size()).append(" 篇）：\n");
            for (SimilarArticleVO hit : hits) {
                observation.append("- 《").append(hit.getTitle()).append("》 作者：")
                        .append(hit.getAuthorName() == null ? "未知" : hit.getAuthorName())
                        .append("（articleId=").append(hit.getArticleId()).append("）\n");
            }
            return observation.toString().trim();
        } catch (Exception e) {
            // 工具失败作为观察结果回喂模型自行调整，不直接中断会话
            log.warn("工具⑩收藏夹检索异常 userId={} queryChars={}", userId,
                    queryText == null ? 0 : queryText.length(), e);
            return "收藏夹检索暂时不可用，请稍后重试。";
        }
    }

    /**
     * 工具⑪：找系列/前作（板块十二 4.1：0 次 LLM 纯检索——同作者条件检索 + 标题语义，
     * 结果即「同作者系列候选」，无命中明确空响应）
     *
     * <p>【口径】序号引用优先（会话内「这篇」默认指结果集定位的文章），无序号时接受
     * articleId；该文不可见/已下架明确响应；检索结果排除该文自身（前作不含自己）。</p>
     *
     * @param articleSeq 会话结果集序号（可空）
     * @param articleId  文章 ID（序号为空时必传）
     * @return 同作者系列候选卡片观察文本（标题/作者/相关度）；失败返回明确原因
     */
    @Tool("查找指定文章的同作者系列/前作：传入文章序号或 articleId，返回同作者语义相关文章列表")
    public String findSeriesArticles(Integer articleSeq, Long articleId) {
        // 会话定位：序号引用依赖会话结果集（一次性请求只能用 articleId）
        AgentCallContext context = AgentCallContext.get();
        Long sessionId = context == null ? null : context.getSessionId();
        Long resolvedId = articleId;
        if (resolvedId == null && articleSeq != null) {
            // 序号解析：过期/越界明确提示（不猜不编造）
            java.util.Optional<Long> seqId = resultRegistry.resolve(sessionId, articleSeq);
            if (seqId.isEmpty()) {
                int size = resultRegistry.size(sessionId);
                return size <= 0
                        ? "当前会话没有可用的结果集（可能已过期），请重新发起检索或提供文章 articleId。"
                        : "序号 " + articleSeq + " 超出当前结果集范围（共 " + size + " 篇），请确认序号或提供 articleId。";
            }
            resolvedId = seqId.get();
        }
        if (resolvedId == null) {
            return "请提供要查前作的文章：结果集序号（如第 1 篇）或 articleId。";
        }
        try {
            // ① 取稿回查（作者与标题来源 + 可见性双保险：已下架/不可见明确响应）
            Result<ContentDTO> resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, resolvedId);
            ContentDTO content = resp == null || !resp.isSuccess() ? null : resp.getData();
            if (content == null || content.getOwnerId() == null
                    || content.getStatus() == null || content.getStatus() != AiContentConstant.STATUS_PUBLISHED
                    || content.getVisibility() == null || content.getVisibility() != AiContentConstant.VISIBILITY_PUBLIC) {
                log.info("工具⑪找系列文章目标不可见 articleId={}", resolvedId);
                return "该文章已下架或不可见，无法查找其系列作品。";
            }
            // ② 同作者条件检索（authorId filter + 标题语义 knn + 排除该文自身）
            SimilarConditionRequestDTO dto = new SimilarConditionRequestDTO();
            dto.setRequestId(context == null ? null : context.getRequestId());
            dto.setQueryText(content.getTitle() == null ? "" : content.getTitle());
            dto.setAuthorId(content.getOwnerId());
            dto.setExcludeArticleId(resolvedId);
            Result<List<SimilarArticleVO>> searchResp = searchClient.similarByCondition(dto);
            List<SimilarArticleVO> hits = searchResp == null || !searchResp.isSuccess() || searchResp.getData() == null
                    ? List.of() : searchResp.getData();
            if (hits.isEmpty()) {
                // 无命中明确空响应（0 次 LLM 纯检索，不编造系列关系）
                return "未找到该作者的其他相关文章（该文可能为该作者的唯一作品）。";
            }
            // ③ 组装同作者系列候选观察文本（语义相关度排序）
            StringBuilder observation = new StringBuilder("同作者系列候选（共 ").append(hits.size()).append(" 篇，")
                    .append("按与该文的语义相关度排序）：\n");
            for (SimilarArticleVO hit : hits) {
                observation.append("- 《").append(hit.getTitle()).append("》（articleId=")
                        .append(hit.getArticleId()).append("）\n");
            }
            return observation.toString().trim();
        } catch (Exception e) {
            // 工具失败作为观察结果回喂模型自行调整，不直接中断会话
            log.warn("工具⑪找系列文章异常 articleId={}", resolvedId, e);
            return "系列作品查找暂时不可用，请稍后重试。";
        }
    }

    /**
     * 工具⑫：热榜上下文（板块十二 4.4 选题灵感底座：content 热榜列表 0 次 LLM，
     * 标题+摘要作分析上下文，榜单数据不进模型记忆——无榜时明确响应不编造）
     *
     * @return 热榜文章观察文本（标题 + 摘要截断）；失败返回明确原因
     */
    @Tool("获取当前站内热榜文章列表（标题与摘要）作为选题灵感分析的数据来源")
    public String getHotTopicContext() {
        try {
            // 热榜数据源：content /article/hot（批次八 8.1 契约复用），榜单排序由数据源保证
            Result<List<com.litisle.litisleapi.dto.content.HotArticleDTO>> resp =
                    contentClient.getHotArticles(agentProperties.getTopicInspirationHotLimit());
            List<com.litisle.litisleapi.dto.content.HotArticleDTO> hotArticles =
                    resp == null || !resp.isSuccess() || resp.getData() == null ? List.of() : resp.getData();
            if (hotArticles.isEmpty()) {
                // 无榜/数据源空：明确响应，防模型凭记忆编造热榜
                return "当前热榜暂无数据，无法做选题灵感分析。";
            }
            StringBuilder observation = new StringBuilder("当前热榜文章（共 ").append(hotArticles.size()).append(" 篇）：\n");
            for (com.litisle.litisleapi.dto.content.HotArticleDTO hot : hotArticles) {
                // 摘要截断（热榜上下文只需主题级信息，防 token 放大）
                String summary = hot.getSummary() == null ? "" : hot.getSummary();
                if (summary.length() > 100) {
                    summary = summary.substring(0, 100) + "……";
                }
                observation.append("- 《").append(hot.getTitle()).append("》")
                        .append(summary.isEmpty() ? "" : "：" + summary).append("\n");
            }
            return observation.toString().trim();
        } catch (Exception e) {
            // 工具失败作为观察结果回喂模型自行调整，不直接中断会话
            log.warn("工具⑫热榜上下文获取异常", e);
            return "热榜数据暂时不可用，无法做选题灵感分析，请稍后重试。";
        }
    }

    /**
     * 工具⑬：风格仿写素材（板块十二 4.6：参考文章风格标签（ai_labels）+ 摘要级正文，
     * 把已有标签从「筛选用」变「生成用」；参考文章需当前公开可见）
     *
     * <p>【口径】风格标签经 SearchClient#getArticleAiLabels 回查（未打标/未入索引返回
     * 空 map——降级为仅正文笔法并如实说明）；正文截断 2000 字符作笔法参考。</p>
     *
     * @param articleSeq 会话结果集序号（可空）
     * @param articleId  文章 ID（序号为空时必传）
     * @return 风格标签 + 参考正文观察文本；失败返回明确原因
     */
    @Tool("获取指定文章的风格仿写素材（风格标签 + 正文笔法参考）：传入文章序号或 articleId")
    public String getStyleImitationMaterial(Integer articleSeq, Long articleId) {
        // 会话定位与序号解析（同工具⑪口径）
        AgentCallContext context = AgentCallContext.get();
        Long sessionId = context == null ? null : context.getSessionId();
        Long resolvedId = articleId;
        if (resolvedId == null && articleSeq != null) {
            java.util.Optional<Long> seqId = resultRegistry.resolve(sessionId, articleSeq);
            if (seqId.isEmpty()) {
                int size = resultRegistry.size(sessionId);
                return size <= 0
                        ? "当前会话没有可用的结果集（可能已过期），请重新发起检索或提供文章 articleId。"
                        : "序号 " + articleSeq + " 超出当前结果集范围（共 " + size + " 篇），请确认序号或提供 articleId。";
            }
            resolvedId = seqId.get();
        }
        if (resolvedId == null) {
            return "请提供要参考的文章：结果集序号（如第 2 篇）或 articleId。";
        }
        try {
            // ① 取稿回查（可见性双保险：参考文章需当前公开可见，已下架明确响应）
            Result<ContentDTO> resp = contentClient.getContent(ModerationConstant.BIZ_TYPE_ARTICLE, resolvedId);
            ContentDTO content = resp == null || !resp.isSuccess() ? null : resp.getData();
            if (content == null
                    || content.getStatus() == null || content.getStatus() != AiContentConstant.STATUS_PUBLISHED
                    || content.getVisibility() == null || content.getVisibility() != AiContentConstant.VISIBILITY_PUBLIC) {
                log.info("工具⑬风格仿写参考文章不可见 articleId={}", resolvedId);
                return "参考文章已下架或不可见，无法提取其风格。";
            }
            // ② 风格标签回查（ai_labels；未打标/未入索引为空 map——降级为仅正文笔法并如实说明）
            String styleLabel = null;
            try {
                Result<java.util.Map<String, String>> labelsResp = searchClient.getArticleAiLabels(resolvedId);
                java.util.Map<String, String> labels = labelsResp == null || !labelsResp.isSuccess()
                        || labelsResp.getData() == null ? java.util.Map.of() : labelsResp.getData();
                styleLabel = labels.get("style");
            } catch (Exception e) {
                // 标签回查失败不中断素材组装：降级为仅正文笔法（如实说明）
                log.warn("工具⑬风格标签回查失败（降级为仅正文笔法）articleId={}", resolvedId, e);
            }
            // ③ 组装仿写素材观察文本（风格标签约束 + 摘要级正文笔法）
            StringBuilder material = new StringBuilder("风格仿写素材（参考文章《")
                    .append(content.getTitle() == null ? "未知" : content.getTitle()).append("》）：\n");
            material.append(styleLabel == null
                    ? "- 风格标签：该文暂无 AI 风格标签（可仅参考正文笔法）\n"
                    : "- 风格标签：" + styleLabel + "\n");
            String body = content.getBody() == null ? "" : content.getBody();
            if (body.length() > COMPARE_ARTICLE_MAX_CHARS) {
                body = body.substring(0, COMPARE_ARTICLE_MAX_CHARS) + "……（已截断）";
            }
            material.append("- 参考正文（笔法参考，截断）：\n").append(body);
            return material.toString();
        } catch (Exception e) {
            // 工具失败作为观察结果回喂模型自行调整，不直接中断会话
            log.warn("工具⑬风格仿写素材获取异常 articleId={}", resolvedId, e);
            return "风格素材获取暂时不可用，请稍后重试。";
        }
    }
}
