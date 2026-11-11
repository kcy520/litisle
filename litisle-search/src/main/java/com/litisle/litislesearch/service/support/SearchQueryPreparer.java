package com.litisle.litislesearch.service.support;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchQueryConstant;
import com.litisle.litislesearch.enums.SearchSortEnum;
import com.litisle.litislesearch.enums.SearchTypeEnum;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 检索参数校验与归一化组件（批次四 4.1，自 SearchServiceImpl 拆出）。
 *
 * 【职责边界】本类负责把原始 {@link SearchRequestDTO} 校验、归一化成只读的 {@link SearchQuery}：
 * 查询词必填/长度/控制字符、type/sort 白名单、分页与深分页窗口、时间范围格式与起止顺序、查询词转义。
 * ES 查询构建、缓存读写、兜底降级均不在此类职责内，由调用方（SearchServiceImpl）编排。
 *
 * 【校验规则】非法参数统一抛 BizException(SEARCH_PARAM_INVALID / SEARCH_PAGE_EXCEEDED)，
 * 缺省值兜底（article/related/第 1 页/每页默认条数），不做静默修正以外的宽容处理。
 *
 * 【配置】分页上限取 litisle.search.page.*（maxPageSize / maxResultWindow），外置可调。
 *
 * @author litisle
 */
@Component
public class SearchQueryPreparer {

    /** 查询词转义保留字符（架构文档 §八-1 清单；仅结构化 DSL 下的纵深防御） */
    private static final String ES_RESERVED_CHARS = "+-=&&||><!(){}[]^\"~*?:\\/";

    /** ES 日期字段查询值的格式（与 mapping date 默认 strict_date_optional_time 兼容） */
    private static final DateTimeFormatter ES_DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 日期筛选输入格式：纯日期（yyyy-MM-dd，长度 10，格式串由常量收口） */
    private static final DateTimeFormatter INPUT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(SearchQueryConstant.PUBLISH_TIME_DATE_PATTERN);

    /** 日期筛选输入格式：空格分隔日期时间（yyyy-MM-dd HH:mm:ss，长度 19） */
    private static final DateTimeFormatter INPUT_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(SearchQueryConstant.PUBLISH_TIME_DATETIME_PATTERN);

    /** search 配置（分页上限取 page.maxPageSize / page.maxResultWindow） */
    private final SearchProperties properties;

    /**
     * 构造组件并注入配置。
     *
     * @param properties search 配置
     */
    public SearchQueryPreparer(SearchProperties properties) {
        this.properties = properties;
    }

    /**
     * 参数校验与归一化。
     *
     * @param request 原始请求
     * @return 归一化后的检索参数
     */
    public SearchQuery prepare(SearchRequestDTO request) {
        /*
         * 业务逻辑步骤：
         * 1. 校验并归一化查询词（必填/长度/控制字符）；
         * 2. 解析 type/sort（白名单枚举，缺省 article/related）；
         * 3. 校验分页（单页上限 + 深分页窗口上限）；
         * 4. 解析时间范围并校验起止顺序；
         * 5. 组装只读 SearchQuery（含转义后查询词与 from 偏移）。
         */

        // 步骤1 前置：请求体缺失：契约层未做校验，提供方兜底拒绝
        if (request == null) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "搜索请求不能为空");
        }
        // 步骤1：查询词：trim 后非空、长度上限内、不含控制字符
        // 变量：归一化后的查询词（未转义，参与缓存指纹与兜底向量化）
        String keyword = normalizeKeyword(request.getQ());
        // 步骤2：类型/排序：仅接受枚举 code/name（大小写不敏感），其余拒绝
        // 变量：检索类型（缺省 article）
        SearchTypeEnum type = resolveType(request.getType());
        // 变量：排序方式（缺省 related）
        SearchSortEnum sort = resolveSort(request.getSort());
        // 步骤3：分页：缺省值兜底 + 单页上限 + 深分页窗口上限
        // 变量：页码（未传取默认第 1 页）
        int pageNum = request.getPageNum() == null
                ? SearchQueryConstant.SEARCH_DEFAULT_PAGE_NUM : request.getPageNum();
        // 变量：每页条数（未传取默认值）
        int pageSize = request.getPageSize() == null
                ? SearchQueryConstant.SEARCH_DEFAULT_PAGE_SIZE : request.getPageSize();
        // 调用分页校验（上限与深分页窗口外置可调）
        validatePage(pageNum, pageSize);
        // 步骤4：时间范围：支持 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss / ISO-8601
        // 变量：发布时间范围起点（纯日期取当天 00:00:00）
        LocalDateTime publishBegin = parsePublishTime(request.getPublishBegin(), true);
        // 变量：发布时间范围终点（纯日期取当天 23:59:59.999999999）
        LocalDateTime publishEnd = parsePublishTime(request.getPublishEnd(), false);
        if (publishBegin != null && publishEnd != null && publishBegin.isAfter(publishEnd)) {
            // 起止颠倒属参数错误：直接拒绝，不自动交换
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "发布时间范围起止颠倒");
        }
        // 步骤5：from 在分页校验通过后计算（乘积已受 max-result-window 约束，不会溢出）
        int from = (pageNum - 1) * pageSize;
        return new SearchQuery(keyword, escapeKeyword(keyword), type, sort, pageNum, pageSize, from,
                publishBegin, publishEnd, request.getTag(), request.getCategory(), request.getZone());
    }

    /**
     * 校验并归一化查询词。
     *
     * @param raw 原始查询词
     * @return trim 后的查询词
     */
    private String normalizeKeyword(String raw) {
        // 必填校验：null/空白均拒绝
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "搜索关键词 q 不能为空");
        }
        String keyword = raw.trim();
        // 长度上限：与 search_history.keyword varchar(64) 同源
        if (keyword.length() > SearchQueryConstant.SEARCH_KEYWORD_MAX_LENGTH) {
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID,
                    "搜索关键词长度不能超过 " + SearchQueryConstant.SEARCH_KEYWORD_MAX_LENGTH + " 个字符");
        }
        // 控制字符拦截：不可见字符可能破坏日志与查询语义
        for (int i = 0; i < keyword.length(); i++) {
            if (Character.isISOControl(keyword.charAt(i))) {
                throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "搜索关键词包含非法控制字符");
            }
        }
        return keyword;
    }

    /**
     * 解析检索类型（缺省 article；非法值拒绝）。
     *
     * @param type 类型 code 或枚举名
     * @return 类型枚举
     */
    private SearchTypeEnum resolveType(String type) {
        if (!StringUtils.hasText(type)) {
            // 缺省按文章检索
            return SearchTypeEnum.ARTICLE;
        }
        String value = type.trim();
        // 白名单匹配：code 与枚举名均接受（大小写不敏感），宽容前端传法
        for (SearchTypeEnum item : SearchTypeEnum.values()) {
            if (item.getCode().equalsIgnoreCase(value) || item.name().equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "type 仅支持 article/book/user");
    }

    /**
     * 解析排序方式（缺省 related；非法值拒绝）。
     *
     * @param sort 排序 code 或枚举名
     * @return 排序枚举
     */
    private SearchSortEnum resolveSort(String sort) {
        if (!StringUtils.hasText(sort)) {
            // 缺省按相关度排序
            return SearchSortEnum.RELATED;
        }
        String value = sort.trim();
        // 白名单匹配：code 与枚举名均接受（大小写不敏感），宽容前端传法
        for (SearchSortEnum item : SearchSortEnum.values()) {
            if (item.getCode().equalsIgnoreCase(value) || item.name().equalsIgnoreCase(value)) {
                return item;
            }
        }
        throw new BizException(ErrorCode.SEARCH_PARAM_INVALID, "sort 仅支持 related/newest/heat");
    }

    /**
     * 分页校验：单页上限 + 深分页窗口上限（pageNum × pageSize ≤ max-result-window）。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     */
    private void validatePage(int pageNum, int pageSize) {
        // 页码下限：ES from 语义要求从 1 开始
        if (pageNum < 1) {
            throw new BizException(ErrorCode.SEARCH_PAGE_EXCEEDED, "pageNum 必须从 1 开始");
        }
        // 单页上限：防一次拉取过大结果集
        if (pageSize < 1 || pageSize > properties.getPage().getMaxPageSize()) {
            throw new BizException(ErrorCode.SEARCH_PAGE_EXCEEDED,
                    "pageSize 必须在 1~" + properties.getPage().getMaxPageSize() + " 之间");
        }
        // long 乘法防溢出：深分页窗口上限与外置的 ES index.max_result_window 对齐
        long windowSize = (long) pageNum * pageSize;
        if (windowSize > properties.getPage().getMaxResultWindow()) {
            throw new BizException(ErrorCode.SEARCH_PAGE_EXCEEDED,
                    "pageNum×pageSize 不能超过 " + properties.getPage().getMaxResultWindow()
                            + "（ES 最大结果窗口）");
        }
    }

    /**
     * 解析发布时间筛选值。
     *
     * @param text  原始字符串（可为空）
     * @param begin true=范围起点（纯日期取当天 00:00:00）；false=范围终点（纯日期取当天 23:59:59.999999999）
     * @return 解析后的时间；未传返回 null
     */
    private LocalDateTime parsePublishTime(String text, boolean begin) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String value = text.trim();
        try {
            // 格式1：纯日期 yyyy-MM-dd（长度固定 10，由常量收口）
            if (value.length() == SearchQueryConstant.PUBLISH_TIME_DATE_LENGTH) {
                LocalDate date = LocalDate.parse(value, INPUT_DATE_FORMATTER);
                // 起点/终点对纯日期做当天首尾扩展，保证筛选闭区间语义
                return begin ? date.atStartOfDay() : date.atTime(LocalTime.MAX);
            }
            // 格式2：yyyy-MM-dd HH:mm:ss
            if (value.length() == SearchQueryConstant.PUBLISH_TIME_DATETIME_PATTERN.length()) {
                return LocalDateTime.parse(value, INPUT_DATE_TIME_FORMATTER);
            }
            // 格式3：ISO-8601（如 2026-11-04T10:00:00）
            return LocalDateTime.parse(value, ES_DATE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            // 三种格式都不匹配：明确报错引导前端，不做静默容错
            throw new BizException(ErrorCode.SEARCH_PARAM_INVALID,
                    "时间范围格式错误，支持 yyyy-MM-dd、yyyy-MM-dd HH:mm:ss 或 ISO-8601");
        }
    }

    /**
     * 查询词转义：对 ES 保留字符加反斜杠（架构文档 §八-1 纵深防御）。
     *
     * 【说明】本服务只使用结构化 DSL（multi_match，非 query_string），用户输入不会被解析为查询语法；
     * 转义是对未来演进（如引入 query_string）的防御，不影响现有 best_fields 匹配。
     *
     * @param keyword 归一化查询词
     * @return 转义后的查询词
     */
    private String escapeKeyword(String keyword) {
        StringBuilder escaped = new StringBuilder(keyword.length() * 2);
        for (int i = 0; i < keyword.length(); i++) {
            char ch = keyword.charAt(i);
            // 保留字符前加反斜杠：即使未来切换 query_string 也不会被解析为查询语法
            if (ES_RESERVED_CHARS.indexOf(ch) >= 0) {
                escaped.append('\\');
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }
}
