package com.litisle.litislesearch.service;

import com.litisle.commoncore.result.PageResult;
import com.litisle.litislesearch.domain.vo.SearchHistoryVO;

/**
 * 搜索历史服务（批次六 6.2）。
 *
 * 【职责】
 * - 搜索成功后异步记录关键词（同词 UPSERT 仅前进时间，单用户超上限懒删最旧）；
 * - 提供本人历史分页查询与清空（查询/清理一律按当前登录用户 ID 收口，防越权）。
 *
 * 【隐私纪律】关键词只落 MySQL，不写 ES/向量库。
 *
 * @author litisle
 */
public interface SearchHistoryService {

    /**
     * 异步记录搜索关键词（同词仅刷新最近搜索时间）。
     *
     * <p>本方法异步执行且内部吞异常：搜索历史是辅助能力，任何失败不得影响检索主链路。</p>
     *
     * @param userId  用户 ID（未登录传 null，直接跳过）
     * @param keyword 原始搜索关键词（内部做去空格/空值/超长过滤）
     */
    void recordAsync(Long userId, String keyword);

    /**
     * 分页查询当前用户的搜索历史（按最近搜索时间降序）。
     *
     * @param userId   用户 ID
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数（不超过 search.page.max-page-size）
     * @return 分页结果（只含 keyword/updatedAt）
     */
    PageResult<SearchHistoryVO> list(Long userId, int pageNum, int pageSize);

    /**
     * 清空当前用户的全部搜索历史。
     *
     * @param userId 用户 ID
     * @return 删除条数
     */
    int clear(Long userId);
}
