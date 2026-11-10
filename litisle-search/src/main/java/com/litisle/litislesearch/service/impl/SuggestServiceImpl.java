package com.litisle.litislesearch.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.CompletionSuggestOption;
import co.elastic.clients.elasticsearch.core.search.Suggestion;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.content.TagSuggestDTO;
import com.litisle.litisleapi.feign.ContentClient;
import com.litisle.litisleapi.vo.search.SuggestVO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchQueryConstant;
import com.litisle.litislesearch.constant.SearchSuggestConstant;
import com.litisle.litislesearch.service.SuggestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 搜索联想服务实现（批次六 6.1）。
 *
 * 实现要点：
 * 1. completion suggester 基于批次一 mapping 已建的 title.completion / authorName.completion，
 *    不新增索引字段、不改 mapping；
 * 2. 标签走 content 只读聚合（best-effort，失败降级为空），不建标签索引；
 * 3. 三类结果按「书名 → 作者 → 标签」顺序合并，按文本去重，总数不超过请求条数；
 * 4. ES 故障只丢书名/作者联想，标签仍返回（反之亦然），联想接口保持可用。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuggestServiceImpl implements SuggestService {

    /** ES 客户端（article_idx completion suggester） */
    private final ElasticsearchClient elasticsearchClient;

    /** search 配置（article 索引名、联想开关与默认条数） */
    private final SearchProperties properties;

    /** content 服务客户端（标签池前缀查询，best-effort） */
    private final ContentClient contentClient;

    /**
     * 联想查询：归一化 → 解析条数 → ES 书名/作者 → content 标签 → 合并去重。
     *
     * @param keyword 用户输入前缀
     * @param size    期望返回条数
     * @return 联想列表
     */
    @Override
    public List<SuggestVO> suggest(String keyword, Integer size) {
        // 步骤1：总开关关闭直接返回空（运维可一键停用联想）
        if (!properties.getSuggest().isEnabled()) {
            return List.of();
        }
        // 步骤2：关键词归一化，空串/单字/超长直接返回空（联想不做强校验报错）
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.isEmpty()
                // 最小长度门槛：单字输入命中率低且放大 completion/标签池查询，企业优化清单 1.1
                || normalized.length() < SearchSuggestConstant.SUGGEST_MIN_KEYWORD_LENGTH
                || normalized.length() > SearchQueryConstant.SEARCH_KEYWORD_MAX_LENGTH) {
            return List.of();
        }
        // 步骤3：解析返回条数（未传/非法取配置默认值，超出默认值按默认值封顶）
        int limit = resolveSize(size);
        // 去重集合：按联想文本去重（同一文本可能同时命中书名与标签）
        Set<String> seenTexts = new LinkedHashSet<>();
        List<SuggestVO> result = new ArrayList<>(limit);
        // 步骤4：ES completion 联想（书名 + 作者），单源故障降级为空
        collectCompletions(normalized, limit, result, seenTexts);
        // 步骤5：content 标签池联想（best-effort），补足剩余条数
        collectTags(normalized, limit, result, seenTexts);
        return result;
    }

    /**
     * 收集 ES completion 联想（书名优先、作者其次）。
     *
     * @param keyword   归一化关键词
     * @param limit     返回条数上限
     * @param result    结果集合（原地追加）
     * @param seenTexts 文本去重集合（原地更新）
     */
    private void collectCompletions(String keyword, int limit, List<SuggestVO> result, Set<String> seenTexts) {
        try {
            // 一次请求取两个 suggester，size(0) 表示不需要 _source 命中
            SearchResponse<Void> response = elasticsearchClient.search(s -> s
                            .index(properties.getIndex().getArticle())
                            .size(0)
                            .suggest(sg -> sg
                                    .suggesters(SearchSuggestConstant.SUGGESTER_NAME_TITLE, fs -> fs
                                            .prefix(keyword)
                                            .completion(c -> c
                                                    .field(SearchSuggestConstant.SUGGEST_FIELD_TITLE_COMPLETION)
                                                    .size(limit)
                                                    .skipDuplicates(true)))
                                    .suggesters(SearchSuggestConstant.SUGGESTER_NAME_AUTHOR, fs -> fs
                                            .prefix(keyword)
                                            .completion(c -> c
                                                    .field(SearchSuggestConstant.SUGGEST_FIELD_AUTHOR_NAME_COMPLETION)
                                                    .size(limit)
                                                    .skipDuplicates(true)))),
                    Void.class);
            // 响应按 suggester 名称分组解析
            Map<String, List<Suggestion<Void>>> suggestMap = response.suggest();
            // 书名联想（KEYWORD）
            appendCompletion(suggestMap.get(SearchSuggestConstant.SUGGESTER_NAME_TITLE),
                    SearchSuggestConstant.SUGGEST_TYPE_KEYWORD, limit, result, seenTexts);
            // 作者联想（AUTHOR），仅补足剩余条数
            appendCompletion(suggestMap.get(SearchSuggestConstant.SUGGESTER_NAME_AUTHOR),
                    SearchSuggestConstant.SUGGEST_TYPE_AUTHOR, limit, result, seenTexts);
        } catch (Exception e) {
            // ES 故障只丢书名/作者联想，不影响标签联想返回
            log.warn("[搜索联想] ES completion 查询失败，降级跳过书名/作者联想", e);
        }
    }

    /**
     * 追加某一 completion suggester 的联想文本。
     *
     * @param suggestions 该 suggester 的响应列表（可为 null）
     * @param type        联想类型
     * @param limit       返回条数上限
     * @param result      结果集合
     * @param seenTexts   文本去重集合
     */
    private void appendCompletion(List<Suggestion<Void>> suggestions, String type, int limit,
                                  List<SuggestVO> result, Set<String> seenTexts) {
        if (suggestions == null || suggestions.isEmpty()) {
            return;
        }
        for (Suggestion<Void> suggestion : suggestions) {
            // 只处理 completion 类型（本请求仅声明 completion suggester）
            if (!suggestion.isCompletion()) {
                continue;
            }
            for (CompletionSuggestOption<Void> option : suggestion.completion().options()) {
                // 达到上限即停止（书名单源最多占满全部名额）
                if (result.size() >= limit) {
                    return;
                }
                addIfAbsent(option.text(), type, seenTexts, result);
            }
        }
    }

    /**
     * 收集 content 标签池联想（best-effort，异常降级为空）。
     *
     * @param keyword   归一化关键词
     * @param limit     返回条数上限
     * @param result    结果集合
     * @param seenTexts 文本去重集合
     */
    private void collectTags(String keyword, int limit, List<SuggestVO> result, Set<String> seenTexts) {
        try {
            Result<List<TagSuggestDTO>> tagResult = contentClient.getTagSuggestions(keyword);
            if (tagResult == null || !tagResult.isSuccess() || tagResult.getData() == null) {
                return;
            }
            for (TagSuggestDTO tag : tagResult.getData()) {
                if (result.size() >= limit) {
                    return;
                }
                addIfAbsent(tag.getName(), SearchSuggestConstant.SUGGEST_TYPE_TAG, seenTexts, result);
            }
        } catch (Exception e) {
            // 标签源故障只丢标签联想（降级由降级工厂/此处双保险）
            log.warn("[搜索联想] content 标签联想失败，降级跳过标签联想", e);
        }
    }

    /**
     * 文本去重后追加联想项（空文本跳过）。
     *
     * @param text      联想文本
     * @param type      联想类型
     * @param seenTexts 文本去重集合
     * @param result    结果集合
     */
    private void addIfAbsent(String text, String type, Set<String> seenTexts, List<SuggestVO> result) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalizedText = text.trim();
        // 已存在同文本则不重复返回
        if (!seenTexts.add(normalizedText)) {
            return;
        }
        result.add(new SuggestVO(type, normalizedText));
    }

    /**
     * 解析返回条数：未传/非法取配置默认值，超出默认值按默认值封顶。
     *
     * @param size 请求条数
     * @return 生效条数（至少 1）
     */
    private int resolveSize(Integer size) {
        int defaultSize = Math.max(1, properties.getSuggest().getSize());
        if (size == null || size < 1) {
            return defaultSize;
        }
        return Math.min(size, defaultSize);
    }
}
