package com.litisle.litisleai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.config.AiAgentProperties;
import com.litisle.litisleai.config.AiProviderProperties;
import com.litisle.litisleai.config.AiRagProperties;
import com.litisle.litisleai.constant.AiPromptConstants;
import com.litisle.litisleai.dto.ExtractedSearchCondition;
import com.litisle.litisleai.dto.SimilarArticleCard;
import com.litisle.litisleai.dto.XiaoyueSimilarRequest;
import com.litisle.litisleai.dto.XiaoyueSimilarResponse;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleai.provider.text.TextChatRequest;
import com.litisle.litisleai.provider.text.TextChatResult;
import com.litisle.litisleai.provider.text.TextModelProvider;
import com.litisle.litisleai.service.support.AiUsageDetail;
import com.litisle.litisleai.service.support.SuggestionValidator;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import com.litisle.litisleapi.dto.content.ArticleIndexDocDTO;
import com.litisle.litisleapi.dto.search.SimilarConditionRequestDTO;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litisleapi.feign.SearchClient;
import com.litisle.litisleapi.util.ArticleVectorTextUtils;
import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import com.litisle.litisleapi.vo.search.UserSearchVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 相似文章推荐编排服务（板块五 5.1/5.2 + 板块八 1.1/1.2：F8 相似推荐 + L0/L1 条件化分层路由）
 *
 * <p>【职责边界】本类收口推荐链路编排：取稿 → 查询文本组装 → 委托 RagSearchService.similarArticles
 * 检索回查 → 计量落明细 → 5.2 可选理由生成（默认关闭）；板块八 1.1 增静态路由——freeText 为空
 * 走 L0 直通（0 次 LLM），非空走 L1 条件管道（委托 ConditionExtractService 提取 + SearchClient
 * 条件版检索）；检索与可见性回查在 RagSearchService / search 服务，模型通道服务理由与条件提取。</p>
 *
 * <p>【边界铁律（F8）】只做相似检索，不输出对作者的负面评价性结论；RAG 缺席（rag.enabled=false）/
 * 取稿失败/检索失败/无命中 → 返回空列表明确响应，不报错不降级抛错；L1 条件提取失败/超时/无有效
 * 条件 → 降级 L0 + 响应标注「未识别自定义条件」，不报错；理由生成失败 → 静默返回无理由卡片。</p>
 *
 * <p>【计量口径】L0 纯检索 token=0 落一条明细（callSeq=1）保证可观测；L1 条件提取为查询期生成
 * （purpose=SIMILAR 走预算判定与计量，重试轮次占 callSeq=1~2），条件版检索 token=0 明细 callSeq=3，
 * 提取后降级 L0 的检索明细 callSeq=4；理由开启时对 TopN 候选按需生成（超限只跳过理由不停检索）；
 * 相似推荐为浏览伴随的只读能力，不做用户级配额拦截。</p>
 *
 * <p>【通俗说明】用户读文章时侧边栏推荐的"找相似文章"功能。分两档：用户没写额外要求
 * 就走 L0 直通——把当前文章（标题+摘要+正文）拿去向量库搜相似的，零成本；用户写了
 * 要求（比如"找 XX 类的短文章"）就走 L1——先让 AI 把这句话翻译成结构化筛选条件，
 * 再带条件检索。任何环节出问题都"温柔降级"：返回空列表或退回 L0 模式并附一句提示，
 * 绝不报错打断阅读。可选的"推荐理由"一句话说明（默认关闭）由 AI 生成，失败就
 * 显示没有理由的普通卡片。会话里的推荐结果还会按序号登记，方便用户说"第 2 篇"
 * 时能找到。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarRecommendService {

    /** 纯检索明细调用序号（L0 直通路径每次推荐固定 1 条 token=0 明细） */
    private static final int RETRIEVAL_CALL_SEQ = 1;

    /** 条件版检索明细调用序号（L1 路径：提取重试轮次占 callSeq 1~2，检索在其后） */
    private static final int COND_RETRIEVAL_CALL_SEQ = 3;

    /** L1 降级 L0 的检索明细调用序号（提取已占 1~2，降级检索再落一条不与条件版冲突） */
    private static final int DEGRADE_RETRIEVAL_CALL_SEQ = 4;

    /** L1 降级提示：条件提取失败/超时/无有效条件（响应标注，不报错） */
    private static final String NOTICE_CONDITION_UNRESOLVED = "未识别自定义条件，已按普通相似推荐返回";

    /** L1 降级提示：条件版检索服务异常 */
    private static final String NOTICE_CONDITION_SEARCH_FAILED = "条件检索暂不可用，已按普通相似推荐返回";

    /** 理由生成调用序号（同一 requestId 下检索明细之后递增，仅 L0 直通路径使用） */
    private static final int REASON_CALL_SEQ = 2;

    /** 作者名解析命中数上限（user_idx 模糊命中集大小，解析时优先精确匹配） */
    private static final int AUTHOR_RESOLVE_LIMIT = 5;

    /** RAG 检索服务（rag.enabled=false 时 Bean 缺席，ObjectProvider 取空 → 空列表响应） */
    private final ObjectProvider<RagSearchService> ragSearchProvider;

    /** 内容服务客户端（源文章回查，查询文本组装） */
    private final ContentClient contentClient;

    /** 搜索服务客户端（板块八 1.1/1.2：条件版相似检索 + 作者名解析 user_idx 查询） */
    private final SearchClient searchClient;

    /** 条件提取服务（板块八 1.2：1 次 LLM 固定管道，不用 Agent 循环） */
    private final ConditionExtractService conditionExtractService;

    /** 会话结果集注册服务（板块九 1.1：推荐结果按会话记序号映射，供结果动作/读后服务引用） */
    private final SessionResultRegistryService resultRegistry;

    /** 会话条件上下文存储（板块十 2.1：上一轮条件 JSON 暂存，多轮调条件的合并基线） */
    private final SessionConditionStoreService conditionStore;

    /** JSON 序列化器（合并后条件对象 → 暂存 JSON） */
    private final ObjectMapper objectMapper;

    /** 文本模型通道（仅 5.2 理由生成使用；enabled=false 时调用抛明确异常转静默跳过） */
    private final TextModelProvider textModelProvider;

    /** 结构化校验器（5.2 理由输出校验） */
    private final SuggestionValidator validator;

    /** 计量明细服务 */
    private final AiUsageRecordService usageRecordService;

    /** 预算分账判定（仅理由生成前置判定） */
    private final AiBudgetService budgetService;

    /** 指标打点 */
    private final AiMetrics metrics;

    /** 通道配置（model 版本快照 / provider 标签） */
    private final AiProviderProperties providerProperties;

    /** Agent 配置（理由生成重试预算复用 structuredRetryBudget） */
    private final AiAgentProperties agentProperties;

    /** RAG 配置（similar 配置组：topK/minScore/理由开关） */
    private final AiRagProperties ragProperties;

    /**
     * 相似文章推荐主流程（POST /ai/xiaoyue/similar 编排入口，板块八 1.1 L0/L1 静态路由）
     *
     * <p>【业务逻辑步骤】
     * 1. 参数校验（requestId/articleId 必传）；
     * 2. RAG 缺席检查（enabled=false → 空列表，F8 不报错）；
     * 3. 静态路由（零成本 if 判断）：freeText 为空 → L0 直通固定相似检索；非空 → L1 条件管道；
     * 4. L0：取稿组装查询文本 → RagSearchService 检索回查 → token=0 明细 → 5.2 可选理由；
     * 5. L1：条件提取（1 次 LLM）→ 条件版检索 → 提取失败/超时/无有效条件降级 L0 + 标注不报错。</p>
     *
     * @param request 推荐请求（requestId/articleId 必传；freeText 可空触发 L1）
     * @param userId  当前登录用户 ID（controller 从 Sa-Token 会话取得；未登录为 null，匿名浏览计量归属为空）
     * @return 推荐响应（cards 卡片列表 + conditional 路由标识 + notice 降级标注；失败路径不报错）
     * @throws BizException 参数缺失（10001/80002）
     *
     * <p>【通俗说明】入口三步：验参数 → 确认 RAG 功能开着（没开直接返回空列表）→
     * 按"用户写没写额外要求"分流：没写走 L0 直通，写了走 L1 条件检索。</p>
     */
    public XiaoyueSimilarResponse similar(XiaoyueSimilarRequest request, Long userId) {
        // ① 参数校验（requestId 是计量幂等键组成，必传）
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(com.litisle.commoncore.exception.ErrorCode.AI_REQUEST_ID_REQUIRED);
        }
        if (request.getArticleId() == null) {
            throw new BizException(com.litisle.commoncore.exception.ErrorCode.PARAM_ERROR, "articleId 必传");
        }
        // ② RAG 缺席：rag.enabled=false 时检索 Bean 整体缺席，按 F8 返回空列表明确响应
        RagSearchService ragSearchService = ragSearchProvider.getIfAvailable();
        if (ragSearchService == null) {
            log.info("RAG 未启用，相似推荐返回空列表 requestId={}", request.getRequestId());
            return wrap(List.of(), false, null);
        }
        // ③ 静态路由（零成本 if 判断，不需要 LLM 判意图）：freeText 为空走 L0，非空走 L1
        if (request.getFreeText() == null || request.getFreeText().isBlank()) {
            return wrap(similarL0(request, userId, ragSearchService, RETRIEVAL_CALL_SEQ), false, null);
        }
        return similarWithCondition(request, userId, ragSearchService);
    }

    /**
     * L0 直通固定相似检索（板块五 5.1/5.2 原链路；callSeq 入参化支撑降级复用）
     *
     * <p>【业务逻辑步骤】
     * 1. 取源文章组装查询文本（标题+摘要+正文截断，与池化后文章向量输入口径对齐；取稿失败按无法推荐处理，返回空列表）；
     * 2. 检索回查（RagSearchService.similarArticles，异常降级空列表并落失败明细）；
     * 3. 纯检索计量（token=0 落一条明细保证可观测）；
     * 4. 组装推荐卡片 + 5.2 可选理由生成（默认关闭）。</p>
     *
     * @param request           推荐请求
     * @param userId            当前登录用户 ID（计量归属）
     * @param ragSearchService  RAG 检索服务（主流程已判缺席）
     * @param retrievalCallSeq  检索明细调用序号（L0 直通=1，L1 降级=4，防与提取明细撞幂等键）
     * @return 推荐卡片列表（无命中/失败时为空列表，不报错）
     *
     * <p>【通俗说明】L0 直通四步：① 取源文章拼查询文本（取不到就返回空列表）；
     * ② 交给 RAG 检索服务搜相似（出错降级空列表并留失败账）；③ 落一条 token=0 的
     * 计量明细（可观测）；④ 组装卡片、登记会话序号映射，可选给前几张卡生成一句话
     * 推荐理由。</p>
     */
    private List<SimilarArticleCard> similarL0(XiaoyueSimilarRequest request, Long userId,
                                               RagSearchService ragSearchService, int retrievalCallSeq) {
        // ① 取源文章组装查询文本（标题+摘要+正文截断，search 板块一 1.4 池化口径对齐；
        // 取稿失败按无法推荐处理，返回空列表）
        ArticleIndexDocDTO article = fetchArticle(request.getArticleId());
        if (article == null) {
            log.warn("相似推荐取稿失败，返回空列表 requestId={} articleId={}", request.getRequestId(), request.getArticleId());
            return List.of();
        }
        String queryText = buildQueryText(article);
        // ② 检索回查（向量化/ES 异常降级空列表——F8 不报错；失败也落明细留痕）
        List<RagSearchService.SimilarArticleHit> hits;
        try {
            hits = ragSearchService.similarArticles(request.getArticleId(), queryText);
        } catch (Exception e) {
            log.warn("相似推荐检索降级，返回空列表 requestId={} articleId={}", request.getRequestId(), request.getArticleId(), e);
            recordRetrievalDetail(request, userId, false, retrievalCallSeq);
            return List.of();
        }
        // ③ 纯检索计量：token=0 仍落一条明细保证可观测（embedding 为本地模型零外部费用）
        recordRetrievalDetail(request, userId, true, retrievalCallSeq);
        // ④ 组装推荐卡片（回查未通过的候选已在检索层移除）
        AiRagProperties.Similar similarConfig = ragProperties.getSimilar();
        List<SimilarArticleCard> cards = hits.stream()
                .map(h -> {
                    SimilarArticleCard card = new SimilarArticleCard();
                    card.setArticleId(h.articleId());
                    card.setTitle(h.title());
                    card.setAuthorId(h.authorId());
                    card.setAuthorName(h.authorName());
                    card.setSimilarity(h.similarity());
                    card.setOutdated(h.outdated());
                    return card;
                })
                .toList();
        // 板块九 1.1：会话请求把本次结果记入序号映射（空结果覆盖清空，防引用陈旧集合）
        recordResultSet(request, cards);
        log.info("相似推荐完成 requestId={} articleId={} hits={}", request.getRequestId(), request.getArticleId(), cards.size());
        // ⑤ 5.2 可选增强：推荐理由生成（默认关闭；失败静默返回无理由卡片，不影响检索结果）
        if (similarConfig.isReasonEnabled() && !cards.isEmpty()) {
            cards = fillReasons(request, cards, similarConfig, userId,
                    article.getTitle() == null ? "" : article.getTitle());
        }
        return cards;
    }

    /**
     * L1 条件化相似检索（板块八 1.1/1.2：条件提取 → 条件版检索，失败降级 L0 不报错）
     *
     * <p>【业务逻辑步骤】
     * 1. 条件提取（1 次 LLM 固定管道；失败/超时降级 L0 + 标注「未识别自定义条件」）；
     * 2. 全字段空 = 无有效条件 → 降级 L0 + 标注；
     * 3. 组装条件版检索请求（作者名→ID 走 SearchClient 查 user_idx 非 LLM，解析失败丢弃该条件）；
     * 4. 条件版检索（search 异常降级 L0 + 标注）；
     * 5. 条件版检索计量（token=0 明细 callSeq=3）+ 组装卡片（conditional=true）。</p>
     *
     * @param request         推荐请求（freeText 非空）
     * @param userId          当前登录用户 ID（计量归属）
     * @param ragSearchService RAG 检索服务（降级 L0 时复用）
     * @return 推荐响应（降级路径 conditional=false 且 notice 标注原因）
     *
     * <p>【通俗说明】L1 条件检索五步：① AI 把用户的自由描述翻译成结构化条件（会话
     * 请求还会带上上一轮的条件做增量合并）；② 翻译出来全是空 = 没识别出有效条件，
     * 退回 L0 并提示；③ 组装检索请求（作者名先查 ID，查不到就丢掉这个条件）；
     * ④ 带条件检索（search 服务异常也退回 L0）；⑤ 组卡片、登记会话序号，
     * 响应里标注"这是条件版检索"。</p>
     */
    private XiaoyueSimilarResponse similarWithCondition(XiaoyueSimilarRequest request, Long userId,
                                                        RagSearchService ragSearchService) {
        // ① 条件提取（1 次 LLM 固定管道；失败/超时降级 L0 + 标注，不报错）
        // 板块十 2.1：会话请求加载上一轮条件 JSON 作为合并基线（首轮/过期按普通提取处理）
        String previousConditionJson = conditionStore.load(request.getSessionId());
        ExtractedSearchCondition condition;
        try {
            condition = conditionExtractService.extract(request.getRequestId(), userId,
                    request.getScene(), request.getFreeText(), previousConditionJson);
        } catch (Exception e) {
            log.warn("条件提取失败，降级 L0 requestId={} freeTextChars={} hasPrevious={}",
                    request.getRequestId(), request.getFreeText().length(), previousConditionJson != null, e);
            return wrap(similarL0(request, userId, ragSearchService, DEGRADE_RETRIEVAL_CALL_SEQ),
                    false, NOTICE_CONDITION_UNRESOLVED);
        }
        // ② 全字段空 = 无有效条件：降级 L0 + 标注（不浪费一次注定无过滤条件的条件版检索）；
        // 会话请求同时清理条件上下文（本轮合并结果为空 = 下一轮按普通提取处理）
        if (!condition.hasAnyCondition()) {
            log.info("条件提取无有效条件，降级 L0 requestId={}", request.getRequestId());
            conditionStore.evict(request.getSessionId());
            return wrap(similarL0(request, userId, ragSearchService, DEGRADE_RETRIEVAL_CALL_SEQ),
                    false, NOTICE_CONDITION_UNRESOLVED);
        }
        // ②' 会话请求保存合并后的条件 JSON（下一轮增量提取的合并基线；保存失败自兜底不阻断）
        if (request.getSessionId() != null) {
            saveConditionContext(request.getSessionId(), condition);
        }
        // ③ 组装条件版检索请求（作者名→ID 解析走 user_idx 普通 Feign 查询非 LLM；解析失败丢弃该条件）
        SimilarConditionRequestDTO conditionRequest = buildConditionRequest(request, condition);
        // ④ 条件版检索（search 异常/非成功响应降级 L0 + 标注；无命中返回空列表属正常结果）
        List<SimilarArticleVO> hits;
        try {
            Result<List<SimilarArticleVO>> resp = searchClient.similarByCondition(conditionRequest);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                throw new IllegalStateException("条件版检索响应异常 code="
                        + (resp == null ? "null" : resp.getCode()));
            }
            hits = resp.getData();
        } catch (Exception e) {
            log.warn("条件版检索失败，降级 L0 requestId={}", request.getRequestId(), e);
            return wrap(similarL0(request, userId, ragSearchService, DEGRADE_RETRIEVAL_CALL_SEQ),
                    false, NOTICE_CONDITION_SEARCH_FAILED);
        }
        // ⑤ 条件版检索计量（token=0 明细 callSeq=3，与提取明细 callSeq 1~2 不冲突）
        recordRetrievalDetail(request, userId, true, COND_RETRIEVAL_CALL_SEQ);
        // ⑥ 组装卡片（articleId/标题/作者/相似度；conditional=true 标识条件版检索生效）
        List<SimilarArticleCard> cards = hits.stream()
                .map(this::toCard)
                .toList();
        // 板块九 1.1：会话请求把条件版检索结果记入序号映射（空结果覆盖清空）
        recordResultSet(request, cards);
        log.info("条件化相似推荐完成 requestId={} articleId={} hits={} aiLabels={} sortHint={}",
                request.getRequestId(), request.getArticleId(), cards.size(),
                condition.getAiLabels().keySet(), condition.getSortHint());
        return wrap(cards, true, null);
    }

    /**
     * 组装条件版检索请求（作者名→ID 解析走 SearchClient 查 user_idx，解析失败丢弃该条件不阻断）
     *
     * @param request   推荐请求（excludeArticleId 取被浏览文章，排除自身铁律不变）
     * @param condition 提取出的结构化条件
     * @return search 条件版检索请求体
     *
     * <p>【通俗说明】把 AI 翻译出的条件逐项填进检索请求体；其中"作者名"需要先换成
     * 作者 ID 才能过滤——换不上就把这条条件扔掉（只影响过滤精度，不影响检索进行），
     * 同时保证被浏览的文章本身不在推荐结果里。</p>
     */
    private SimilarConditionRequestDTO buildConditionRequest(XiaoyueSimilarRequest request,
                                                             ExtractedSearchCondition condition) {
        SimilarConditionRequestDTO dto = new SimilarConditionRequestDTO();
        dto.setRequestId(request.getRequestId());
        // 排除自身：被浏览文章本身不进推荐结果（F8 铁律，条件版检索同样生效）
        dto.setExcludeArticleId(request.getArticleId());
        dto.setQueryText(condition.getQueryText());
        dto.setCategory(condition.getCategory());
        dto.setTags(condition.getTags());
        dto.setAiLabels(condition.getAiLabels());
        dto.setWordCountMin(condition.getWordCountMin());
        dto.setWordCountMax(condition.getWordCountMax());
        dto.setPublishTimeStart(condition.getTimeStart());
        dto.setPublishTimeEnd(condition.getTimeEnd());
        dto.setTopK(condition.getTopK());
        dto.setSortHint(condition.getSortHint());
        // 作者名→ID 解析（普通 Feign 查询非 LLM）：解析失败仅丢弃该条件，不阻断检索
        if (condition.getAuthorName() != null) {
            Long authorId = resolveAuthorId(condition.getAuthorName());
            if (authorId == null) {
                log.info("作者名解析失败，丢弃该条件 requestId={} authorNameChars={}",
                        request.getRequestId(), condition.getAuthorName().length());
            } else {
                dto.setAuthorId(authorId);
            }
        }
        if (condition.getExcludeAuthorName() != null) {
            Long excludeAuthorId = resolveAuthorId(condition.getExcludeAuthorName());
            if (excludeAuthorId == null) {
                log.info("排除作者名解析失败，丢弃该条件 requestId={}", request.getRequestId());
            } else {
                dto.setExcludeAuthorIds(List.of(excludeAuthorId));
            }
        }
        return dto;
    }

    /**
     * 作者名 → 作者 ID 解析（user_idx 笔名查询：优先精确匹配笔名，次取最高相关命中）
     *
     * @param authorName 作者笔名
     * @return 作者 ID；无命中或查询异常返回 null（调用方丢弃该条件）
     *
     * <p>【通俗说明】拿作者名去用户搜索接口查 ID：优先找笔名完全一致的，找不到就取
     * 第一个模糊命中的；查不到或接口挂了返回 null，调用方自动放弃"按作者过滤"。</p>
     */
    private Long resolveAuthorId(String authorName) {
        try {
            Result<List<UserSearchVO>> resp = searchClient.searchUsers(authorName, AUTHOR_RESOLVE_LIMIT);
            List<UserSearchVO> users = resp == null || !resp.isSuccess() || resp.getData() == null
                    ? List.of() : resp.getData();
            if (users.isEmpty()) {
                return null;
            }
            // 优先精确匹配笔名（模糊命中的首条可能是近似笔名）
            return users.stream()
                    .filter(u -> authorName.equals(u.getPenName()))
                    .findFirst()
                    .or(() -> users.stream().findFirst())
                    .map(UserSearchVO::getUserId)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("作者名解析查询异常 authorNameChars={}", authorName.length(), e);
            return null;
        }
    }

    /**
     * 条件版检索命中转推荐卡片
     *
     * @param vo search 返回的相似文章 VO
     * @return 推荐卡片（outdated/reason 无条件版数据源，保持默认值）
     *
     * <p>【通俗说明】把 search 服务返回的命中记录转成统一格式的推荐卡片。</p>
     */
    private SimilarArticleCard toCard(SimilarArticleVO vo) {
        SimilarArticleCard card = new SimilarArticleCard();
        card.setArticleId(vo.getArticleId());
        card.setTitle(vo.getTitle());
        card.setAuthorId(vo.getAuthorId());
        card.setAuthorName(vo.getAuthorName());
        card.setSimilarity(vo.getScore() == null ? 0.0D : vo.getScore());
        return card;
    }

    /**
     * 保存会话条件上下文（板块十 2.1：合并后的条件对象序列化为 JSON 暂存，
     * 供下一轮增量提取作合并基线；序列化失败降级为不保存并告警，不阻断检索）
     *
     * @param sessionId 会话 ID
     * @param condition 合并后的结构化条件（已过注册表校验）
     *
     * <p>【通俗说明】把本轮合并好的检索条件存进会话暂存区，下一轮用户追加条件时
     * 就能"在上次基础上加要求"。存失败也不影响本轮，下一轮按全新条件处理。</p>
     */
    private void saveConditionContext(Long sessionId, ExtractedSearchCondition condition) {
        try {
            conditionStore.save(sessionId, objectMapper.writeValueAsString(condition));
        } catch (Exception e) {
            // 序列化失败只影响下一轮合并基线（按普通提取处理），不阻断本轮检索
            log.warn("会话条件上下文序列化失败（下一轮按普通提取处理）sessionId={}", sessionId, e);
        }
    }

    /**
     * 会话结果集记录（板块九 1.1：会话请求把本次推荐/检索结果按展示顺序记入序号映射）
     *
     * <p>【口径】仅会话请求记录（sessionId 为空的一次性浏览不记）；空结果集覆盖清空
     * （最近一次检索无命中 → 旧序号失效，防引用陈旧集合）；记录失败内部自兜底不阻断推荐主链路。</p>
     *
     * @param request 推荐请求（sessionId 可空）
     * @param cards   本次推荐结果（按展示顺序）
     *
     * <p>【通俗说明】只有会话请求才登记：把本次推荐的文章按展示顺序记成"第 1 篇、
     * 第 2 篇……"的映射，之后用户说"帮我总结第 2 篇"就能定位到。空结果会覆盖
     * 清空旧映射，防止引用已经过期的列表。</p>
     */
    private void recordResultSet(XiaoyueSimilarRequest request, List<SimilarArticleCard> cards) {
        // 一次性请求无会话维度：不记录
        if (request.getSessionId() == null) {
            return;
        }
        // 按展示顺序提取 articleId（序号 = 下标 + 1），空列表交由注册服务覆盖清空
        List<Long> articleIds = cards.stream()
                .map(SimilarArticleCard::getArticleId)
                .filter(java.util.Objects::nonNull)
                .toList();
        resultRegistry.record(request.getSessionId(), articleIds);
    }

    /**
     * 包装推荐响应（L0/L1 统一出参）
     *
     * @param cards      推荐卡片列表
     * @param conditional 是否走 L1 条件版检索
     * @param notice     降级标注（正常路径为 null）
     * @return 推荐响应
     *
     * <p>【通俗说明】统一出参打包器：卡片列表 + "是否条件检索"标记 + 降级提示语
     * 三样装进响应。</p>
     */
    private XiaoyueSimilarResponse wrap(List<SimilarArticleCard> cards, boolean conditional, String notice) {
        XiaoyueSimilarResponse response = new XiaoyueSimilarResponse();
        response.setCards(cards);
        response.setConditional(conditional);
        response.setNotice(notice);
        return response;
    }

    /**
     * 回查源文章（标题/摘要/正文用于组装查询文本；失败返回 null 由调用方返回空列表）。
     *
     * <p>search 板块一 1.4 起改走 getArticleIndexDoc（ArticleIndexDocDTO 带摘要字段，
     * ContentDTO 无摘要），保证查询文本能覆盖「标题+摘要+正文」池化可比口径。</p>
     *
     * @param articleId 文章 ID
     * @return 文章索引回查 DTO；不存在/服务异常返回 null
     *
     * <p>【通俗说明】去 content 服务取源文章（要标题、摘要、正文三样来拼查询文本），
     * 取不到返回 null，调用方按"无法推荐"返回空列表。</p>
     */
    private ArticleIndexDocDTO fetchArticle(Long articleId) {
        try {
            Result<ArticleIndexDocDTO> resp = contentClient.getArticleIndexDoc(articleId);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                return null;
            }
            return resp.getData();
        } catch (Exception e) {
            // 日志纪律：只记 ID 不打正文
            log.warn("相似推荐取稿异常 articleId={}", articleId, e);
            return null;
        }
    }

    /**
     * 组装向量化查询文本：标题 + 摘要 + 正文截断（与 search 侧 buildEmbeddingInput 同一实现）。
     *
     * <p>search 板块一 1.4 口径对齐：查询向量与池化后的文章向量输入口径可比
     * （两边都是「标题+摘要+长正文」，knn 分才有可比性）；拼接与截断规则直接复用
     * litisle-api 共享单点 {@link ArticleVectorTextUtils#buildEmbeddingInput}（2000 字上限同源），
     * 正文经 HTML 转纯文本后传入，与 search 侧索引输入形态一致。</p>
     *
     * @param article 源文章（索引回查 DTO）
     * @return 查询文本（标题+摘要+正文截断）
     *
     * <p>【通俗说明】拼"拿去搜相似"的文本：标题 + 摘要 + 正文（HTML 先转纯文本），
     * 截断规则复用共享工具类，保证和文章入库时的向量口径完全一致——两边口径一样，
     * 相似度分数才可比。</p>
     */
    private String buildQueryText(ArticleIndexDocDTO article) {
        // 正文 HTML 转纯文本（共享单点规则，与 search 侧索引输入形态一致）
        String plainBody = ArticleVectorTextUtils.toPlainText(article.getContentHtml());
        // 复用共享拼接单点：标题+摘要+正文截断（2000 上限内置，与文章向量输入同口径）
        return ArticleVectorTextUtils.buildEmbeddingInput(article.getTitle(), article.getSummary(), plainBody);
    }

    /**
     * 5.2 推荐理由生成：TopN 候选一句话理由（结构化输出 + 校验回喂重试，失败静默跳过）
     *
     * <p>【预算口径】purpose=SIMILAR 走预算判定；EXCEEDED 只跳过理由生成（检索卡片照常返回），
     * 不抛错不静默放行——理由是增强能力非主结果。</p>
     *
     * @param request       推荐请求（计量归属）
     * @param cards         检索得到的卡片列表（按相似度降序）
     * @param similarConfig 相似推荐配置
     * @param userId        当前登录用户 ID（计量归属；未登录为 null）
     * @param sourceTitle   源文章标题（主流程已取稿，理由 prompt 用）
     * @return 卡片列表（前 TopN 张尽量带上 reason，生成失败保持 null）
     *
     * <p>【通俗说明】给前 TopN 张卡片生成"为什么推荐"的一句话理由：先查预算（超限
     * 只跳过理由、卡片照常返回），再把源文章标题和候选清单交给模型；输出经校验
     * （序号范围 + 字数）后按序号回填到卡片上。模型调用失败或校验反复不过都静默
     * 放弃——理由是锦上添花，不能让它影响推荐本身。</p>
     */
    private List<SimilarArticleCard> fillReasons(XiaoyueSimilarRequest request, List<SimilarArticleCard> cards,
                                                 AiRagProperties.Similar similarConfig, Long userId, String sourceTitle) {
        // 预算判定：超限只跳过理由（可观测：记配额指标），检索结果不受影响
        if (budgetService.check(AiPurposeEnum.SIMILAR) == AiBudgetDecision.EXCEEDED) {
            log.warn("相似推荐理由生成预算超限，跳过理由生成 requestId={}", request.getRequestId());
            metrics.recordQuotaExceeded(AiPurposeEnum.SIMILAR.getCode());
            return cards;
        }
        int topN = Math.min(similarConfig.getReasonTopN(), cards.size());
        // 组装候选清单（序号 1 起，对应卡片列表前 TopN 位）
        StringBuilder candidateLines = new StringBuilder();
        for (int i = 0; i < topN; i++) {
            candidateLines.append(i + 1).append(".《").append(cards.get(i).getTitle()).append("》\n");
        }
        String originalTask = String.format(AiPromptConstants.SIMILAR_REASON_PROMPT,
                sourceTitle, candidateLines, similarConfig.getReasonMaxChars());
        // 自校验回喂循环（重试预算与结构化管道同源：1 次首轮 + structuredRetryBudget 次回喂）
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
                recordReasonSuccess(request, userId, result);
                // service 侧校验（不依赖模型自觉）：序号范围 + 一句话字数
                List<SuggestionValidator.ReasonItem> reasons = validator.validateReasons(
                        result.getContent(), topN, similarConfig.getReasonMaxChars());
                metrics.recordGeneration(normalizeScene(request.getScene()), AiMetrics.RESULT_SUCCESS);
                // 按序号回填理由（模型只给部分候选时，缺失候选保持 null）
                Map<Integer, String> reasonMap = new HashMap<>();
                for (SuggestionValidator.ReasonItem item : reasons) {
                    reasonMap.put(item.getIndex(), item.getReason());
                }
                List<SimilarArticleCard> filled = new java.util.ArrayList<>(cards);
                for (int i = 0; i < topN; i++) {
                    filled.get(i).setReason(reasonMap.get(i + 1));
                }
                return filled;
            } catch (BizException e) {
                // 模型通道失败：失败也留明细；重试预算内再试，预算耗尽静默返回无理由卡片
                recordReasonFail(request, userId);
                metrics.recordGeneration(normalizeScene(request.getScene()), AiMetrics.RESULT_FAIL);
                lastError = "模型调用失败：" + e.getMessage();
            } catch (IllegalArgumentException e) {
                // 校验不过：模型调用本身成功（已计量），记录具体错误回喂重试
                log.info("推荐理由输出校验未通过 requestId={} attempt={} error={}",
                        request.getRequestId(), attempt, e.getMessage());
                metrics.recordGeneration(normalizeScene(request.getScene()), AiMetrics.RESULT_FAIL);
                lastError = e.getMessage();
            } finally {
                metrics.recordLatency(AiPurposeEnum.SIMILAR.getCode(), System.currentTimeMillis() - start);
            }
        }
        // 重试预算耗尽：理由生成静默放弃（F8 边界铁律：不报错），卡片列表原样返回
        log.warn("推荐理由生成终态失败（重试预算耗尽），返回无理由卡片 requestId={}", request.getRequestId());
        return cards;
    }

    /**
     * 纯检索计量留痕：token=0 明细一条（成功/失败均落，保证可观测）
     *
     * <p>modelVersion 取本地 embedding 模型标识——embedding 为本地模型不产生外部费用；
     * ai_pricing 未配置该版本时成本按 0 核算（建议补 0 单价配置行消除缺价告警）。</p>
     *
     * @param request  推荐请求
     * @param userId   当前登录用户 ID（计量归属；未登录为 null）
     * @param success  检索是否成功（失败 token 同为 0，status=FAIL 留痕）
     * @param callSeq  调用序号（L0 直通=1 / 条件版=3 / L1 降级=4，防与提取明细撞幂等键）
     *
     * <p>【通俗说明】纯检索也记账：检索本身不花模型的钱（本地向量化 token=0），
     * 但落一条明细保证"每次推荐都有迹可查"。</p>
     */
    private void recordRetrievalDetail(XiaoyueSimilarRequest request, Long userId, boolean success, int callSeq) {
        AiUsageDetail detail = AiUsageDetail.builder()
                .requestId(request.getRequestId())
                .callSeq(callSeq)
                .userId(userId)
                .purpose(AiPurposeEnum.SIMILAR)
                .scene(request.getScene())
                .modelVersion(ragProperties.getOllamaModel())
                .tokenIn(0)
                .tokenOut(0)
                .degraded(0)
                .occurredAt(LocalDateTime.now())
                .build();
        if (success) {
            usageRecordService.recordSuccess(detail);
        } else {
            usageRecordService.recordFail(detail);
        }
    }

    /**
     * 理由生成成功计量留痕（token 取 provider 返回值）
     *
     * <p>【通俗说明】理由生成成功的记账。</p>
     */
    private void recordReasonSuccess(XiaoyueSimilarRequest request, Long userId, TextChatResult result) {
        usageRecordService.recordSuccess(AiUsageDetail.builder()
                .requestId(request.getRequestId())
                .callSeq(REASON_CALL_SEQ)
                .userId(userId)
                .purpose(AiPurposeEnum.SIMILAR)
                .scene(request.getScene())
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
     * 理由生成失败计量留痕（失败即无成本防护：token 按 0 落库）
     *
     * <p>【通俗说明】理由生成失败的记账。</p>
     */
    private void recordReasonFail(XiaoyueSimilarRequest request, Long userId) {
        usageRecordService.recordFail(AiUsageDetail.builder()
                .requestId(request.getRequestId())
                .callSeq(REASON_CALL_SEQ)
                .userId(userId)
                .purpose(AiPurposeEnum.SIMILAR)
                .scene(request.getScene())
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
     * <p>【通俗说明】场景没传就记成 default，避免监控指标出现空标签。</p>
     */
    private String normalizeScene(String scene) {
        return scene == null || scene.isBlank() ? AiMetrics.SCENE_DEFAULT : scene;
    }
}
