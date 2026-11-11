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
 *用户在搜索框打字时，每敲几个字符，前端就把当前输入发给后端，后端返回一列候选词（书名、作者名、标签），前端渲染成下拉框。这就是联想（autocomplete），特点是： 触发极频繁（几乎每键一次）、每次返回极少数据、用户对延迟极敏感 （超过 100ms 就感觉“卡”）。整条链路的所有设计都是围绕“又快又稳”这四个字。
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

//    主流方案的核心洞察是： 联想的本质是“别人都在搜什么”，不是“库里有什么文档” 。所以 Google 联想 "jav" 时给你的不是标题含 jav 的文档，而是`java` 、`javascript` 这些 高频查询词 ——它们来自查询日志的离线挖掘（频次统计、纠错归并、同义合并）。
//    你的项目联想的是“存在的文档标题/作者/标签”，这叫 document suggest ；主流的“搜过什么联想什么”叫 query suggest 。前者实现简单、永远有结果；后者覆盖率更高（用户搜出 0 结果的词也能被下一个用户联想引导）。
//    这是微服务下的典型权衡： 大厂趋向“合并查询、缓存兜底”（因为规模大、跳数敏感），中小规模服务趋向“数据不搬家、就近缓存” 。你的选择和项目规模匹配。

//    你的方案 = document suggest（completion suggester）+ 标签池前缀查询（Redis 缓存） ，是中小规模站点的标准起步形态，不算偏门。与主流的差距不在“对错”而在“没到那个规模”：
//    当前：文档字段联想 + 标签（前缀）
//            ↓ 词表升级
//    加 query suggest：聚合 search_history 高频词 → suggest 词表（一次离线任务）
//            ↓ 匹配升级
//    edge n-gram 或词表容错（中缀/拼音/错别字）
//            ↓ 规模升级
//    独立 suggest 服务 + 本地 trie + 网关缓存

//完整链路
//    用户敲入 "ja"（前端通常有 200-300ms 防抖，停顿才发请求）
//            → 闸门检查                    0ms（纯内存判断）
//            → ES 前缀树查询（书名+作者）    2~5ms（内存遍历，不碰磁盘）
//            → Feign → content            调用content去MySQL查询标签
//      → Redis 缓存命中           +1~2ms   ← 99% 的请求止步于此
//      → （未命中）MySQL 查询      +1~5ms   ← 仅 8 分钟窗口内首次出现的输入
//  → 合并去重                     0ms
//    合计：命中缓存 ~5-10ms；未命中 ~10-15ms；感知阈值 ~100ms，余量 6 倍以上

    //容错线
//    ES 挂   → 丢书名/作者，标签照常    → 下拉框仍有内容
//    content 挂 → 丢标签，书名/作者照常  → 下拉框仍有内容
//    两个都挂 → 联想返回空列表          → 但搜索主链路完全不受影响（联想是独立接口）

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

        //查完 content 侧实现，结论是： 这条链路早就做过性能优化，担心的“每次都查 MySQL”实际不会发生 。逐层看：
//        ## 实际链路：MySQL 是最后一级，且前面有三道闸
//        前端每键触发 suggest
//      ↓ ① search 侧闸门
//            SuggestServiceImpl：总开关关闭/空串/单字(<2字符)/超长 → 直接返回空，一个请求都不发
//      ↓ ② ES completion suggester（书名+作者）
//            基于 title.completion / authorName.completion 内存 FST 结构，毫秒级，不碰磁盘
//      ↓ ③ content 侧 Redis 缓存（TagServiceImpl.searchByPrefix）
//            缓存命中 → 直接返回，【不碰 MySQL】
//      ↓ ④ 缓存未命中才查 MySQL
//            LIKE '前缀%' + 转义 + 只取前 N 条 + use_count 倒序

//        content 侧 TagServiceImpl.java#L80-L120 已经做了“企业优化清单 1.2”的缓存方案：
//        - 有结果 ：缓存 8 分钟 ± 10% 抖动（防雪崩）
//        - 空结果 ：缓存 60 秒短 TTL（防穿透——用户输入乱串时不会每键都打到 MySQL）
//        - LIKE 转义 ：`%` /`_` 被转义，用户输入不会把全表拖出来
//        所以真实的 MySQL 命中率取决于“联想词的重复度”——热门前缀（"ja"、"py"、"spring"）几乎永远命中缓存，只有长尾输入在 8 分钟窗口内第一次出现时才查一次库。而`tag` 表本身是个小表（一个站点几百到几千个标签），前缀 LIKE 走索引范围扫描也就亚毫秒级


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
