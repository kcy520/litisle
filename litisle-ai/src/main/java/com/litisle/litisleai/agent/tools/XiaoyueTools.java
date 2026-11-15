package com.litisle.litisleai.agent.tools;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.content.ContentDTO;
import com.litisle.litisleapi.dto.content.TagSuggestDTO;
import com.litisle.litisleapi.feign.ContentClient;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 小悦能力工具集（板块二 2.2 Function Calling：AiServices 按注解自动注册给 Agent；
 * 板块四 4.2：工具④知识库检索由占位转正）
 *
 * <p>【铁律】工具只读、不写业务表；工具失败不抛异常中断会话，而是返回失败观察文本，
 * 由模型自行调整（回喂不中断口径）；出入参日志不带正文全文（只记长度与标识）。</p>
 *
 * <p>【占位口径】敏感词预检（复用 DFA 闸门）暂无内部端点，占位实现返回「工具暂不可用」，
 * 模型改走无预检回答，闸门兜底仍由调用方执行；知识库检索（rag.enabled=true）已转正为
 * RAG 检索工具（topK+阈值+引用回查），rag.enabled=false 或 RAG 不可用时返回占位说明
 * （Q54 降级：模型改走无检索回答并标注「未引用站内检索结果」）。</p>
 *
 * <p>【数据源】稿件与标签池唯一数据源在 content 服务，经 ContentClient 回查（fail-fast
 * 降级工厂已就绪）；工具结果回喂文本出站时统一经过脱敏装饰器（P1 出站红线）。</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XiaoyueTools {

    /** 内容 bizType：1-文章（ModerationBizTypeEnum 口径） */
    private static final int BIZ_TYPE_ARTICLE = 1;

    /** RAG 检索降级文案（rag.enabled=false 或无有效命中：模型改走无检索回答，Q54） */
    private static final String RAG_DEGRADED_HINT =
            "知识库检索暂不可用或无相关资料：请基于稿件内容直接回答，并在回答开头注明「未引用站内检索结果」。";

    /** 内容服务客户端（取稿件/查标签池） */
    private final ContentClient contentClient;

    /** RAG 检索服务（板块四 4.2 转正；rag.enabled=false 时 Bean 缺席，工具降级） */
    private final org.springframework.beans.factory.ObjectProvider<com.litisle.litisleai.service.RagSearchService> ragSearchProvider;

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
            Result<ContentDTO> resp = contentClient.getContent(BIZ_TYPE_ARTICLE, articleId);
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
        // 占位实现：DFA 闸门内部端点未落地（2.2 条目口径），注册占位保持工具面稳定
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
        com.litisle.litisleai.service.RagSearchService ragSearchService = ragSearchProvider.getIfAvailable();
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
}
