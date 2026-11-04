package com.litisle.litislesearch.service;

import com.litisle.litisleapi.vo.search.SuggestVO;

import java.util.List;

/**
 * 搜索联想服务（批次六 6.1）。
 *
 * 【职责】聚合三类联想并去重返回：
 * - 书名/作者：article_idx 的 completion suggester（title.completion / authorName.completion）；
 * - 标签：content 标签池前缀查询（标签 SSOT 在 content，search 不建标签索引）。
 *
 * 【降级口径】整体为空/词非法返回空列表；ES 或 content 单源故障只丢该源结果，不拖垮整个联想接口。
 *
 * @author litisle
 */
public interface SuggestService {

    /**
     * 联想查询。
     *
     * @param keyword 用户输入前缀
     * @param size    期望返回条数（为空或越界时取配置默认值并封顶）
     * @return 去重合并后的联想列表（无命中为空列表）
     */
    List<SuggestVO> suggest(String keyword, Integer size);
}
