package com.litisle.litislesearch.service.impl;

import com.litisle.litisleapi.vo.search.SimilarArticleVO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchRerankConstant;
import com.litisle.litislesearch.rerank.RerankProvider;
import com.litisle.litislesearch.service.RerankService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地 reranker 精排服务实现（search 板块三 3.2 升级②）。
 *
 * 【流程】触发判定（开关 + queryText + 提供方就绪）→ 候选「标题 + 摘要」组装截断
 * → 提供方逐对打分 → 按相关性分降序重排（打分失败项退回基线位置）
 * → score 更新为相关性分 → 截断 topK。
 *
 * 【降级语义】未启用 / queryText 为空 / 提供方未就绪 / 整批打分失败 / 分数与候选数量不符，
 * 一律 fail-open 退回融合基线排序截断 topK——重排失败不影响查询主链路可用性，结果不劣化。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankServiceImpl implements RerankService {

    /** search 配置（读取 litisle.search.rerank.* 开关、提供方、候选截断参数） */
    private final SearchProperties properties;

    /** rerank 提供方集合（按配置精确匹配选择；无实现时集合为空，不阻塞启动） */
    private final ObjectProvider<RerankProvider> rerankProviders;

    /** 「提供方未就绪」告警只打一次，避免逐查询重排时日志刷屏 */
    private final AtomicBoolean unavailableWarned = new AtomicBoolean(false);

    /**
     * 重排能力是否就绪。
     *
     * <p>【通俗说明】做什么：判断重排功能现在能不能用。
     * 怎么做：两个条件都满足才算能用——1）配置开关 rerank.enabled 是打开状态；
     * 2）配置里指定的重排服务能找到，而且它的服务地址和模型名都填好了（见 resolveProvider）。
     * 任何一个条件不满足就返回 false，查询会直接沿用原来的融合排序。</p>
     *
     * @return true=开关打开且配置的提供方已注册并可用
     */
    @Override
    public boolean enabled() {
        return properties.getRerank().isEnabled() && resolveProvider().isPresent();
    }

    /**
     * 对候选文章按 (queryText, 候选摘要) 相关性重排（0 次 LLM）。
     *
     * <p>【业务逻辑步骤】
     * 1. 触发判定——候选不足 2 条 / queryText 为空 / 未启用：无重排意义，退回基线列表截断 topK；
     * 2. 按配置解析提供方：未就绪仅首次告警后退回基线（避免逐查询日志刷屏）；
     * 3. 候选文本组装：标题 + 摘要（摘要缺失退化仅标题），按模型输入上限截断防请求被拒；
     * 4. 调用提供方逐对打分：整批失败 / 分数与候选数量不符退回基线截断 topK；
     * 5. 按相关性分降序重排（打分失败项视为最低分保持基线相对顺序沉底），
     *    score 更新为相关性分，截断 topK 返回。</p>
     *
     * <p>【通俗说明】做什么：给一段查询文本和一批候选文章，返回按「和查询文本相关程度」
     * 从高到低排好的文章列表，最多 topK 条——1）每篇文章先变成一段文字（标题+摘要）；
     * 2）发给重排服务打相关性分；3）按分数从高到低排队并把 score 字段改成相关性分；
     * 4）截取前 topK 条返回。任何一步做不成（开关没开/查询文本为空/找不到重排服务/调用失败/
     * 分数数量对不上）都不重排，按传进来的原顺序截取前 topK 条返回，结果不会比不重排差。</p>
     *
     * @param queryText  查询文本（可空）
     * @param candidates 候选文章列表（进来时已是融合排序的顺序，可空）
     * @param topK       最终返回条数
     * @return 重排后的文章列表（score=相关性分）；没做成重排时返回原顺序的列表截前 topK 条
     */
    @Override
    public List<SimilarArticleVO> rerank(String queryText, List<SimilarArticleVO> candidates, int topK) {

        /*
         * 业务逻辑步骤：
         * 1. 基线兜底判定：候选不足 2 条 / queryText 为空 / 未启用 / 候选为空 → 退回基线截断 topK；
         * 2. 按配置解析提供方：未就绪仅首次告警后退回基线（重排属增强能力，缺实现不阻断查询）；
         * 3. 组装候选「标题 + 摘要」文本并截断，下标与候选一一对应；
         * 4. 调用提供方打分，整批失败或长度不符时退回基线截断 topK；
         * 5. 按分数降序稳定重排 + 更新 score + 截断 topK；打分失败项沉底保持基线相对顺序。
         */

        // 步骤1：基线兜底判定——候选不足 2 条（单条重排无排序意义）或查询文本为空，直接退回基线
        if (candidates == null || candidates.size() <= 1 || !StringUtils.hasText(queryText)) {
            return baseline(candidates, topK);
        }
        // 开关关闭属正常配置，静默退回基线（不告警）
        if (!properties.getRerank().isEnabled()) {
            return baseline(candidates, topK);
        }
        // 步骤2：按配置解析提供方；未接入时仅首次告警，避免逐查询重排日志刷屏
        Optional<RerankProvider> provider = resolveProvider();
        if (provider.isEmpty()) {
            // compareAndSet 保证多线程并发时告警只打一次
            if (unavailableWarned.compareAndSet(false, true)) {
                log.warn("[rerank 精排] rerank.enabled=true 但未找到可用提供方（provider={}），本次起退回基线排序；"
                                + "选型后再实现 RerankProvider 适配器",
                        properties.getRerank().getProvider());
            }
            return baseline(candidates, topK);
        }

        // 步骤3：候选「标题 + 摘要」组装（摘要缺失退化仅标题；空文档按占位串防协议层歧义）
        List<String> documents = new ArrayList<>(candidates.size());
        for (SimilarArticleVO candidate : candidates) {
            documents.add(buildCandidateText(candidate));
        }
        // 步骤4：调用提供方逐对打分（返回 null 属提供方整批失败约定语义）
        List<Double> scores = provider.get().rerank(queryText, documents);
        // 整批失败 / 分数与候选数量不符：退回基线截断 topK，结果不劣化
        if (scores == null || scores.size() != candidates.size()) {
            log.warn("[rerank 精排] 提供方打分失败或数量不符，退回基线排序：provider={}, model={}, candidates={}",
                    provider.get().providerName(), provider.get().modelName(), candidates.size());
            return baseline(candidates, topK);
        }

        // 步骤5：按相关性分降序稳定重排——打分失败（null）项视为最低分沉底，保持基线相对顺序
        List<SimilarArticleVO> ranked = new ArrayList<>(candidates);
        ranked.sort((left, right) -> {
            Double leftScore = scores.get(candidates.indexOf(left));
            Double rightScore = scores.get(candidates.indexOf(right));
            // 双方均未打分：保持基线相对顺序（稳定排序下返回 0 即不交换）
            if (leftScore == null && rightScore == null) {
                return 0;
            }
            // 单方未打分：未打分项沉底
            if (leftScore == null) {
                return 1;
            }
            if (rightScore == null) {
                return -1;
            }
            // 双方已打分：按相关性分降序
            return Double.compare(rightScore, leftScore);
        });
        // score 更新为相关性分（重排生效后排序权威方为 reranker，融合分不再承载排序语义）
        for (int i = 0; i < ranked.size(); i++) {
            Double score = scores.get(candidates.indexOf(ranked.get(i)));
            if (score != null) {
                ranked.get(i).setScore(score);
            }
        }
        // 截断 topK 返回
        return baseline(ranked, topK);
    }

    /**
     * 候选重排文本组装：标题 + 摘要，按 reranker 输入上限截断。
     *
     * <p>【通俗说明】做什么：把一篇文章变成给重排模型看的一段文字。
     * 做什么：给一篇文章对象，返回「标题 + 摘要」拼成的一段文字，作为模型给这篇文章打分的依据。
     * 怎么做：1）有标题就先把标题放进去；2）有摘要就接着把摘要拼在后面（中间隔一个空格）；
     * 3）拼好后如果超过 512 个字符，只保留前 512 个字符（重排模型一次能读的长度有限，太长会被拒绝）；
     * 4）标题和摘要都没有就返回空字符串。
     * 注意：截断只是让这篇文章的分数可能没那么准，不会把这篇文章从候选里去掉。</p>
     *
     * @param candidate 候选文章 VO
     * @return 截断后的候选文本（标题摘要均缺失时返回空串，防协议层歧义）
     */
    private String buildCandidateText(SimilarArticleVO candidate) {
        // 标题优先（相关性权重最高）；摘要拼接补充语义
        StringBuilder text = new StringBuilder();
        if (candidate != null && StringUtils.hasText(candidate.getTitle())) {
            text.append(candidate.getTitle().trim());
        }
        if (candidate != null && StringUtils.hasText(candidate.getSummary())) {
            text.append(" ").append(candidate.getSummary().trim());
        }
        String doc = text.toString();
        // 超 reranker 输入上限截断（bge-reranker 类模型普遍 512 token；截断只影响打分精度，不丢弃候选）
        if (doc.length() > SearchRerankConstant.RERANK_DOC_MAX_CHARS) {
            doc = doc.substring(0, SearchRerankConstant.RERANK_DOC_MAX_CHARS);
        }
        return doc;
    }

    /**
     * 按配置解析可用提供方：provider=none 或未配置时视为未接入。
     *
     * <p>【通俗说明】做什么：从 Spring 容器里所有已注册的重排服务实现（RerankProvider）中，挑出配置指定的那一个。
     * 怎么做：1）先看配置项 rerank.provider 写的是什么；没配置，或者写成 none（表示没接入），
     * 直接返回"没有"，不再往下找；2）配置了具体名字（比如 openai-compatible），
     * 就在所有实现里找名字和它相同的；3）找到名字相同的还不够，还要这个实现自己的配置也齐全
     * （服务地址和模型名都填了，见它的 available 方法）才算可用；
     * 4）找到可用的就返回它；一个都没有就返回"没有"。</p>
     *
     * @return 匹配配置且本地配置完整的提供方；无则 empty
     */
    private Optional<RerankProvider> resolveProvider() {
        String configured = properties.getRerank().getProvider();
        // 未配置或显式 none：视为未接入（不要求提供方实现存在）
        if (!StringUtils.hasText(configured)
                || SearchRerankConstant.RERANK_PROVIDER_NONE.equalsIgnoreCase(configured.trim())) {
            return Optional.empty();
        }
        // 按 providerName 精确匹配多条提供方实现，并过滤本地配置未就绪的实现
        return rerankProviders.orderedStream()
                .filter(provider -> configured.trim().equalsIgnoreCase(provider.providerName()))
                .filter(RerankProvider::available)
                .findFirst();
    }

    /**
     * 基线列表截断（所有降级路径的统一出口：原序截断 topK，结果不劣化）。
     *
     * <p>【通俗说明】做什么：给一个列表和条数上限，返回最多 topK 条。
     * 怎么做：1）列表是空的，或者列表长度本来就不超过 topK，原样返回，什么都不动；
     * 2）列表比 topK 长，就复制前 topK 条返回（复制是为了不动调用方的原列表）。
     * 为什么所有没做成重排的情况最后都走这里：重排做不成时沿用传进来的原顺序，
     * 结果和不做重排一模一样，保证「重排失败不会让结果变差」。</p>
     *
     * @param list 基线列表（可空）
     * @param topK 返回条数上限
     * @return 截断后的列表（原序）
     */
    private List<SimilarArticleVO> baseline(List<SimilarArticleVO> list, int topK) {
        if (list == null || list.size() <= topK) {
            return list;
        }
        return new ArrayList<>(list.subList(0, topK));
    }
}
