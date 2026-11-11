package com.litisle.litislesearch.service.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.content.HotArticleDTO;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litisleapi.vo.search.ArticleDocVO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchEmbeddingConstant;
import com.litisle.litislesearch.constant.SearchFieldConstant;
import com.litisle.litislesearch.constant.SearchGateConstant;
import com.litisle.litislesearch.constant.SearchQueryConstant;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.enums.SearchTypeEnum;
import com.litisle.litislesearch.service.VectorService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 检索兜底与降级组件（批次四 4.2 / 批次A A3 / 批次八 8.1，自 SearchServiceImpl 拆出）。
 *
 * 【职责边界】本类承载检索的两条非主链路：
 * ① 空结果兜底（source=fallback）：查询词实时 Embedding → article_idx KNN → 失败退 content 热门榜 → 仍失败空页；
 * ② ES 整体不可用降级（source=DEGRADED）：直接取 content 热门榜，失败抛 SEARCH_ES_UNAVAILABLE。
 * 不做参数校验、不做缓存读写、不做指标记账（由调用方 SearchServiceImpl 编排）。
 *
 * 【数据来源】content 热门榜（Feign `/api/feign/content/hot-articles`，与对外 `/article/hot` 同源）。
 * 兜底与降级的区别在「触发条件」而非数据源：兜底仅用于 ES 正常但检索无命中；
 * 降级用于 ES 整体不可用；两者统一复用同一 content 热门端点与同一 VO 转换。
 *
 * 【缓存策略】兜底/降级结果 source 均非 ES，由 HotQueryCacheService#write 的 source 校验拦住，
 * 天然不写热点查询缓存：热门列表与查询词无关，按查询键缓存只会重复占内存并掩盖 ES 恢复。
 *
 * 【失败语义】兜底链路逐级收敛（KNN 失败转热门榜、热门榜失败转空页），绝不阻塞主链路；
 * 降级链路失败向上抛 SEARCH_ES_UNAVAILABLE，不静默返回空列表（否则用户无法感知服务不可用）。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchFallbackService {

    /** ES 客户端（KNN 兜底检索） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（KNN 参数、兜底超时、索引名） */
    private final SearchProperties properties;

    /** 向量化服务（查询词实时 Embedding；提供方未接入时返回 null） */
    private final VectorService vectorService;

    /** content 服务客户端（热门榜降级数据源） */
    private final ContentClient contentClient;

    /** ES 查询 DSL 构建组件（复用高亮配置与 term 过滤，避免重复实现） */
    private final EsSearchQueryBuilder queryBuilder;

    /** 结果转换组件（KNN 结果与热门条目转 VO 复用统一口径） */
    private final SearchResultConverter resultConverter;

    /**
     * 兜底 Embedding 线程池：VectorService 可能阻塞在远程模型调用，
     * 用独立线程池承载并配合 Future 超时，避免拖垮 Web 请求线程
     */
    private final ExecutorService embedExecutor = Executors.newFixedThreadPool(2, runnable -> {
        // 兜底线程设为守护线程，避免停机时因子任务阻塞退出
        Thread thread = new Thread(runnable, "search-embed-fallback");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 停机时释放兜底线程池。
     */
    @PreDestroy
    public void shutdown() {
        embedExecutor.shutdown();
    }

    /**
     * 空结果兜底：实时 Embedding → KNN；失败再退 content 热门榜（getHotArticles）；仍失败返回空页。
     *
     * @param query 检索参数
     * @return 兜底结果（source=fallback，不写缓存）
     */
    public SearchResultVO fallback(SearchQuery query) {
        /*
         * 业务逻辑步骤：
         * 1. book/user 无兜底数据源（无向量字段也无热门榜语义）：直接返回空页；
         * 2. 一级兜底：查询词实时 Embedding（超时/失败返回 null，维度不符同样跳过）；
         * 3. 二级兜底：以向量做 KNN 语义检索，命中则返回（source=fallback）；
         * 4. 三级兜底：KNN 失败/无命中转 content 热门榜；热门榜也失败返回空页。
         */

// 一级兜底 = 把查询词实时向量化
// 它做什么： 主链路关键词检索 0 命中后，调用 Embedding 模型把用户输入的查询文本（如“猫不喝水怎么办”）变成一个 1024 维向量。它本身 不产生搜索结果 ，只是给二级兜底（KNN）准备“弹药”——有了查询向量，才能去和文章的`vec_dense` 向量算相似度。
// 1. 为什么叫“一级” ：它是兜底链的第一步，定位是“准备输入”而非“给出结果”——真正给出结果的是二级（KNN）和三级（热门榜）。

// 主链路：关键词全文检索（ES 正常，但 0 命中）
//    │ 还是搜不到
//    ▼
// 一级兜底：把查询词实时向量化（embedWithTimeout，L119）
//    │ 拿到 1024 维向量；失败/超时/维度不符 → 跳到三级
//    ▼
// 二级兜底：拿这个向量做 KNN 语义检索（knnFallback，L123）★ 你问的就是这步
//    │ 命中 >0 条 → 直接返回；ES 异常或 0 命中 → 跳到三级
//    ▼
// 三级兜底：调 content 热门榜（hotArticleFallback，L134）
//    │ 连热门榜都失败 → 返回空页


        // 步骤1：仅文章索引有 vec_dense 向量兜底能力；book/user 无可用兜底数据，直接返回空页
        if (query.getType() != SearchTypeEnum.ARTICLE) {
            log.info("[检索兜底] book/user 无兜底数据源，返回空页：type={}, q={}",
                    query.getType().getCode(), query.getKeyword());
            return emptyResult(query.getPageNum(), query.getPageSize());
        }
        // 步骤2：一级兜底：查询词实时 Embedding（超时/失败返回 null）
        List<Float> vector = embedWithTimeout(query.getKeyword());
        if (vector != null && vector.size() == SearchEmbeddingConstant.EMBEDDING_DIMS) {
            try {
                // 步骤3：KNN 语义近邻检索（向量维度与索引 mapping 对齐才执行）
                SearchResultVO knnResult = knnFallback(query, vector);
                if (knnResult.getTotal() != null && knnResult.getTotal() > 0) {
                    return knnResult;
                }
            } catch (Exception e) {
                // KNN 失败（ES 抖动/查询异常）继续走三级兜底，不阻塞主链路
                log.warn("[检索兜底] KNN 检索失败，转 content 热门榜：q={}", query.getKeyword(), e);
            }
        }
        // 步骤4：三级兜底：复用 content 热门榜（与批次八 ES 不可用降级同源，但触发语义不同）
        try {
            return hotArticleFallback(query);
        } catch (Exception e) {
            // 热门列表也失败：返回空页（兜底失败不阻塞主链路）
            log.error("[检索兜底] content 热门榜获取失败，返回空页：q={}", query.getKeyword(), e);
            return emptyResult(query.getPageNum(), query.getPageSize());
        }
    }

    /**
     * ES 整体不可用时的热门降级（批次A A3 定案 / 批次八 8.1）。
     *
     * 【数据来源】content 热门榜（Feign `/api/feign/content/hot-articles`，与对外 `/article/hot` 同源：
     * content 批次E P5 起主路径为 CONTENT_FEED_HOT_ZSET 热度榜，榜空回退 view_cnt 直查）。
     * 与批次四空结果兜底的区别在「触发条件」而非数据源：兜底仅用于 ES 正常但检索无命中（source=fallback），
     * 本降级用于 ES 整体不可用（source=DEGRADED）；两者现已统一复用同一 content 热门端点。
     *
     * 【缓存策略】降级结果不写热点查询缓存（也不设短 TTL）：
     * ① 热门列表与查询词无关，按查询键缓存会把同一份热门复制到每个关键词下，白占内存且无收益；
     * ② 一旦写入，ES 恢复后缓存 TTL 内仍会持续返回降级结果，掩盖服务已恢复的事实；
     * ③ 现有 HotQueryCacheService#write 只缓存 source=ES 结果，本分支直接返回、不经过写缓存，
     * 与兜底链路保持同一口径。
     *
     * 【失败语义】content 热门获取失败（网络故障/非成功响应/数据为空）→ 抛 SEARCH_ES_UNAVAILABLE，
     * 不静默返回空列表（否则用户会误以为「搜索无结果」，无法感知服务不可用）。
     *
     * 【分页语义】热门榜只有 TopN、没有深分页能力，降级固定返回第一页（pageNum=1）；
     * total 取本次返回条数（无法得知热榜全量，前端据此隐藏后续翻页）。
     *
     * @param query 检索参数
     * @return 降级结果（source=DEGRADED，不写缓存）
     */
    public SearchResultVO degradedResult(SearchQuery query) {
        /*
         * 业务逻辑步骤：
         * 1. book/user 检索没有对应热门数据源：返回空页 + DEGRADED 标记；
         * 2. 调 content 热门榜接口取当页条数的热门文章（暂不支持降级分页）；
         * 3. 非 success 响应或 data 为 null：收敛为 SEARCH_ES_UNAVAILABLE 抛出；
         * 4. 热门条目转检索契约 VO，组装 DEGRADED 结果返回。
         */

        // 步骤1：book/user 无热门降级数据源：返回空页 + DEGRADED 标记，前端据此提示「搜索不可用」
        if (query.getType() != SearchTypeEnum.ARTICLE) {
            log.warn("[检索降级] book/user 无热门降级数据源，返回空页：type={}, q={}",
                    query.getType().getCode(), query.getKeyword());
            return resultConverter.buildResult(1, query.getPageSize(),
                    SearchQueryConstant.SEARCH_SOURCE_DEGRADED, 0L, null, null, null);
        }
        // 变量：content 热门文章列表
        List<HotArticleDTO> hotList;
        try {
            // 步骤2：取当前页条数的热门文章（暂不支持降级分页，理由见方法注释）
            Result<List<HotArticleDTO>> response = contentClient.getHotArticles(query.getPageSize());
            // 步骤3：远程返回非成功码或 data 为 null：视为热门获取失败（Feign 网络故障由降级工厂抛 BizException）
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试");
            }
            hotList = response.getData();
        } catch (BizException e) {
            // 已是 SEARCH_ES_UNAVAILABLE（远程业务失败）或降级工厂抛出的远程异常：统一收敛为搜索不可用
            if (ErrorCode.SEARCH_ES_UNAVAILABLE.getCode() == e.getCode()) {
                throw e;
            }
            log.error("[检索降级] 获取 content 热门列表失败，降级失败：q={}", query.getKeyword(), e);
            throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试", e);
        } catch (Exception e) {
            log.error("[检索降级] 获取 content 热门列表异常，降级失败：q={}", query.getKeyword(), e);
            throw new BizException(ErrorCode.SEARCH_ES_UNAVAILABLE, "搜索服务暂不可用，请稍后重试", e);
        }
        // 步骤4：热门条目转检索契约 VO（与空结果兜底共用同一转换，保证两种链路字段口径一致）
        List<ArticleDocVO> articles = toArticleDocs(hotList);
        log.warn("[检索降级] ES 不可用，返回 content 热门列表：q={}, size={}", query.getKeyword(), articles.size());
        return resultConverter.buildResult(1, query.getPageSize(), SearchQueryConstant.SEARCH_SOURCE_DEGRADED,
                (long) articles.size(), articles, null, null);
    }

    /**
     * content 热门条目 → 文章检索契约 VO（空结果兜底与 ES 不可用降级共用）。
     * <p>高亮/热度/tags 等 ES 专有字段没有对应数据，保持 null，前端按普通卡片渲染；
     * 空条目或缺主键的脏数据直接跳过，不让单条数据拖垮整页。</p>
     *
     * @param hotList content 热门文章列表（可为空集合）
     * @return 文章结果 VO 列表
     */
    private List<ArticleDocVO> toArticleDocs(List<HotArticleDTO> hotList) {
        List<ArticleDocVO> articles = new ArrayList<>(hotList.size());
        for (HotArticleDTO hot : hotList) {
            // 脏数据（空条目/缺主键）跳过，避免单条异常导致整页失败
            if (hot == null || hot.getArticleId() == null) {
                continue;
            }
            ArticleDocVO vo = new ArticleDocVO();
            vo.setArticleId(hot.getArticleId());
            vo.setTitle(hot.getTitle());
            vo.setSummary(hot.getSummary());
            vo.setAuthorId(hot.getAuthorId());
            // publishTime 映射为检索契约的 updateTime（与正常检索结果的展示字段保持一致）
            vo.setUpdateTime(hot.getPublishTime());
            articles.add(vo);
        }
        return articles;
    }

    /**
     * 一级兜底：查询词实时向量化（受 fallback.embedding-timeout 约束）。
     *
     * @param text 查询词（未转义原词）
     * @return 1024 维向量；超时/异常/提供方未接入时返回 null
     */
    private List<Float> embedWithTimeout(String text) {
        // 超时下限 1ms：配置异常（0/负数）时仍能快速失败而不是无限等待
        long timeoutMs = Math.max(1, properties.getFallback().getEmbeddingTimeout().toMillis());
        // 提交到独立线程池：模型调用可能长时间阻塞，不能占用 Web 请求线程等待
        Future<List<Float>> future = embedExecutor.submit(() -> vectorService.embed(text));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时：取消任务并转入三级兜底（无向量可用，KNN 二级跳过）
            future.cancel(true);
            log.warn("[检索兜底] 查询词向量化超时（{}ms），转 content 热门榜：q={}", timeoutMs, text);
            return null;
        } catch (InterruptedException e) {
            // 线程中断：恢复中断标记后转入三级兜底（无向量可用，KNN 二级跳过）
            Thread.currentThread().interrupt();
            log.warn("[检索兜底] 查询词向量化被中断，转 content 热门榜：q={}", text);
            return null;
        } catch (Exception e) {
            // 模型调用失败/提供方未接入（返回 null 属正常降级路径）：转入三级兜底（无向量可用，KNN 二级跳过）
            log.warn("[检索兜底] 查询词向量化失败，转 content 热门榜：q={}", text, e);
            return null;
        }
    }

    /**
     * KNN 兜底：以查询词向量在 article_idx 中做语义近邻检索。
     *
     * @param query  检索参数
     * @param vector 查询词向量
     * @return KNN 结果（source=fallback；仅返回第一页，KNN 不支持 from 深翻）
     * @throws Exception ES 访问异常
     */
    private SearchResultVO knnFallback(SearchQuery query, List<Float> vector) throws Exception {
        SearchProperties.Knn knnConfig = properties.getKnn();
        // k 必须 ≥ size，numCandidates 必须 ≥ k
        int k = query.getPageSize();
        int candidates = Math.max(knnConfig.getNumCandidates(), k);
        KnnSearch knn = KnnSearch.of(builder -> builder
                .field(SearchFieldConstant.FIELD_VEC_DENSE)
                .queryVector(vector)
                .k(k)
                .numCandidates(candidates)
                // 可见性双保险：KNN 结果同样只允许 PUBLIC（复用 DSL 构建组件的 term 过滤）
                .filter(queryBuilder.termFilter(SearchFieldConstant.FIELD_VISIBILITY,
                        SearchGateConstant.VISIBILITY_PUBLIC)));
        SearchRequest request = new SearchRequest.Builder()
                .index(properties.getIndex().getArticle())
                .size(k)
                .trackTotalHits(t -> t.enabled(true))
                .knn(knn)
                // 高亮口径与常规文章检索一致（复用 DSL 构建组件）
                .highlight(queryBuilder.buildArticleHighlight())
                .build();
        SearchResponse<ArticleIndexDoc> response =
                elasticsearchClient.search(request, ArticleIndexDoc.class);
        SearchResultVO result = resultConverter.toArticleResult(response, query);
        // 兜底结果固定第一页（KNN 无 from 翻页语义）
        result.setPageNum(1);
        result.setSource(SearchQueryConstant.SEARCH_SOURCE_FALLBACK);
        return result;
    }

    /**
     * 三级兜底：复用 content 热门榜（Feign `/api/feign/content/hot-articles`）。
     *
     * <p>【触发语义】仅在 ES 正常但检索无命中（以及 KNN 无结果）时进入；
     * ES 整体不可用走 {@link #degradedResult(SearchQuery)}，不经过本方法。</p>
     * <p>【数据源】与批次八降级同源（CONTENT_FEED_HOT_ZSET，榜空回退 view_cnt），口径一致。</p>
     * <p>【缓存策略】与降级一致：source 仍为 fallback（非 ES），不写热点查询缓存；
     * 热门结果与查询词无关，按查询键缓存只会重复占内存并掩盖 ES 恢复。</p>
     * <p>【分页语义】热门榜只有 TopN，无深分页能力：固定返回第一页、total=当页条数，
     * 与降级一致；调用方按 source=fallback 的既有契约处理。</p>
     *
     * @param query 检索参数
     * @return 热门列表（source=fallback，不写缓存）
     * @throws Exception content 远程异常（由 fallback 调用方收敛为空页）
     */
    private SearchResultVO hotArticleFallback(SearchQuery query) throws Exception {
        // 取当前页条数的热门文章（暂不支持分页，理由见方法注释）
        Result<List<HotArticleDTO>> response = contentClient.getHotArticles(query.getPageSize());
        // 非成功响应：兜底失败但 ES 仍可用，不抛 SEARCH_ES_UNAVAILABLE（与降级失败语义区分）
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new IllegalStateException("content 热门列表获取失败");
        }
        // 热门条目转检索契约 VO（与降级共用转换，字段口径一致）
        List<ArticleDocVO> articles = toArticleDocs(response.getData());
        return resultConverter.buildResult(1, query.getPageSize(), SearchQueryConstant.SEARCH_SOURCE_FALLBACK,
                (long) articles.size(), articles, null, null);
    }

    /**
     * 空结果（兜底失败或 book/user 无兜底数据）。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @return 空结果（source=fallback，三个命中列表均为空集合）
     */
    private SearchResultVO emptyResult(int pageNum, int pageSize) {
        return resultConverter.buildResult(pageNum, pageSize,
                SearchQueryConstant.SEARCH_SOURCE_FALLBACK, 0L, null, null, null);
    }
}
