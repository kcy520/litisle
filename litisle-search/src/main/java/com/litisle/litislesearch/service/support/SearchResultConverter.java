package com.litisle.litislesearch.service.support;

import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.litisle.litisleapi.vo.search.ArticleDocVO;
import com.litisle.litisleapi.vo.search.BookDocVO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litisleapi.vo.search.UserDocVO;
import com.litisle.litislesearch.constant.SearchFieldConstant;
import com.litisle.litislesearch.constant.SearchQueryConstant;
import com.litisle.litislesearch.domain.entity.ArticleIndexDoc;
import com.litisle.litislesearch.domain.entity.BookIndexDoc;
import com.litisle.litislesearch.domain.entity.UserIndexDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 检索结果转换组件（批次四 4.1，自 SearchServiceImpl 拆出）。
 *
 * 【职责边界】本类只做「ES 响应 → 检索契约 VO」的纯映射：
 * 按索引类型抽取命中列表（含主键解析、高亮提取）、组装 SearchResultVO 骨架；
 * 不触网、不做校验、不做缓存与指标，供 SearchServiceImpl（常规检索）与
 * SearchFallbackService（KNN 兜底）复用同一转换口径。
 *
 * 【容错】单条文档脏数据（source 缺失/主键非法）只跳过该条并告警，不让整页失败；
 * ES total 缺失时退回当前页条数（保证前端分页组件不因 total 缺失崩溃）。
 *
 * @author litisle
 */
@Slf4j
@Component
public class SearchResultConverter {

    /**
     * 文章结果转换。
     *
     * @param response ES 响应
     * @param query    检索参数
     * @return 检索结果（source 由调用方设置）
     */
    public SearchResultVO toArticleResult(SearchResponse<ArticleIndexDoc> response, SearchQuery query) {
        List<ArticleDocVO> articles = new ArrayList<>();
        for (Hit<ArticleIndexDoc> hit : response.hits().hits()) {
            ArticleIndexDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            // 主键异常文档跳过（索引脏数据不应让整页失败）
            Long articleId = parseId(doc.getId(), SearchFieldConstant.FIELD_ID);
            if (articleId == null) {
                continue;
            }
            ArticleDocVO vo = new ArticleDocVO();
            vo.setArticleId(articleId);
            vo.setTitle(doc.getTitle());
            vo.setSummary(doc.getSummary());
            vo.setTags(doc.getTags());
            vo.setAuthorId(parseId(doc.getAuthorId(), SearchFieldConstant.FIELD_AUTHOR_ID));
            vo.setAuthorName(doc.getAuthorName());
            vo.setHeat(doc.getHeat() == null ? null : BigDecimal.valueOf(doc.getHeat()));
            vo.setUpdateTime(doc.getUpdateTime());
            // 高亮优先正文，其次摘要/标题
            vo.setHighlight(firstHighlight(hit.highlight(), List.of(
                    SearchFieldConstant.FIELD_CONTENT, SearchFieldConstant.FIELD_SUMMARY,
                    SearchFieldConstant.FIELD_TITLE)));
            articles.add(vo);
        }
        return buildResult(query.getPageNum(), query.getPageSize(), SearchQueryConstant.SEARCH_SOURCE_ES,
                totalOf(response, articles.size()), articles, null, null);
    }

    /**
     * 书籍结果转换。
     *
     * @param response ES 响应
     * @param query    检索参数
     * @return 检索结果（source 由调用方设置）
     */
    public SearchResultVO toBookResult(SearchResponse<BookIndexDoc> response, SearchQuery query) {
        List<BookDocVO> books = new ArrayList<>();
        for (Hit<BookIndexDoc> hit : response.hits().hits()) {
            BookIndexDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            // 主键异常文档跳过（索引脏数据不应让整页失败）
            Long bookId = parseId(doc.getId(), SearchFieldConstant.FIELD_ID);
            if (bookId == null) {
                continue;
            }
            BookDocVO vo = new BookDocVO();
            vo.setBookId(bookId);
            vo.setTitle(doc.getTitle());
            vo.setSummary(doc.getSummary());
            vo.setCategory(doc.getCategory());
            vo.setAuthorName(doc.getAuthorName());
            // 批次五 B4 已补 book_idx.authorId 映射：解析回填作者 ID（存量未回填文档为 null）
            vo.setAuthorId(parseId(doc.getAuthorId(), SearchFieldConstant.FIELD_AUTHOR_ID));
            vo.setUpdateTime(doc.getUpdateTime());
            vo.setHighlight(firstHighlight(hit.highlight(), List.of(
                    SearchFieldConstant.FIELD_SUMMARY, SearchFieldConstant.FIELD_TITLE,
                    SearchFieldConstant.FIELD_AUTHOR_NAME)));
            books.add(vo);
        }
        return buildResult(query.getPageNum(), query.getPageSize(), SearchQueryConstant.SEARCH_SOURCE_ES,
                totalOf(response, books.size()), null, books, null);
    }

    /**
     * 用户结果转换。
     *
     * @param response ES 响应
     * @param query    检索参数
     * @return 检索结果（source 由调用方设置）
     */
    public SearchResultVO toUserResult(SearchResponse<UserIndexDoc> response, SearchQuery query) {
        List<UserDocVO> users = new ArrayList<>();
        for (Hit<UserIndexDoc> hit : response.hits().hits()) {
            UserIndexDoc doc = hit.source();
            if (doc == null) {
                continue;
            }
            // 主键异常文档跳过（索引脏数据不应让整页失败）
            Long userId = parseId(doc.getId(), SearchFieldConstant.FIELD_ID);
            if (userId == null) {
                continue;
            }
            UserDocVO vo = new UserDocVO();
            vo.setUserId(userId);
            vo.setPenName(doc.getPenName());
            vo.setBio(doc.getBio());
            vo.setHighlight(firstHighlight(hit.highlight(), List.of(
                    SearchFieldConstant.FIELD_BIO, SearchFieldConstant.FIELD_PEN_NAME)));
            users.add(vo);
        }
        return buildResult(query.getPageNum(), query.getPageSize(), SearchQueryConstant.SEARCH_SOURCE_ES,
                totalOf(response, users.size()), null, null, users);
    }

    /**
     * 组装检索结果（三个命中列表只保留对应类型，其余为空集合）。
     *
     * <p>公开给兜底/降级链路复用（热门榜转 VO、空页组装），保证同一契约口径。</p>
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param source   结果来源
     * @param total    命中总数
     * @param articles 文章列表（非文章类型传 null）
     * @param books    书籍列表（非书籍类型传 null）
     * @param users    用户列表（非用户类型传 null）
     * @return 检索结果 VO
     */
    public SearchResultVO buildResult(int pageNum, int pageSize, String source, Long total,
                                      List<ArticleDocVO> articles, List<BookDocVO> books,
                                      List<UserDocVO> users) {
        SearchResultVO result = new SearchResultVO();
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setSource(source);
        result.setTotal(total);
        // 非对应类型的命中列表统一空集合，前端按空列表渲染
        result.setArticleList(articles == null ? Collections.emptyList() : articles);
        result.setBookList(books == null ? Collections.emptyList() : books);
        result.setUserList(users == null ? Collections.emptyList() : users);
        return result;
    }

    /**
     * 取命中总数（ES total 缺失时退回当前页条数）。
     *
     * @param response ES 响应
     * @param fallback 当前页条数
     * @return 命中总数
     */
    private long totalOf(SearchResponse<?> response, int fallback) {
        return response.hits().total() == null ? fallback : response.hits().total().value();
    }

    /**
     * 提取首个高亮片段：按 preferred 字段顺序找，找不到再取任意字段。
     *
     * @param highlight 高亮结果（field → 片段列表）
     * @param preferred 字段优先级
     * @return 高亮片段；无高亮返回 null
     */
    private String firstHighlight(Map<String, List<String>> highlight, List<String> preferred) {
        if (highlight == null || highlight.isEmpty()) {
            return null;
        }
        // 按业务优先级顺序找首个有片段的字段（文章：正文 > 摘要 > 标题）
        for (String field : preferred) {
            List<String> fragments = highlight.get(field);
            if (fragments != null && !fragments.isEmpty()) {
                return fragments.get(0);
            }
        }
        // 优先字段全部无片段：取任意有片段的字段兜底（ES 可能只命中非优先字段）
        for (List<String> fragments : highlight.values()) {
            if (fragments != null && !fragments.isEmpty()) {
                return fragments.get(0);
            }
        }
        return null;
    }

    /**
     * 文档主键字符串转 Long（非法值告警并跳过该条）。
     *
     * @param value ES 文档主键
     * @param field 字段名（日志定位）
     * @return Long 主键；缺失或非法返回 null
     */
    private Long parseId(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            // 索引脏数据不中断整页：告警留痕后跳过该条
            log.warn("[检索] 索引文档主键非法，跳过该条：field={}, value={}", field, value);
            return null;
        }
    }
}
