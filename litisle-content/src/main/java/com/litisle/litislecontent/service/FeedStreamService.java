package com.litisle.litislecontent.service;

import com.litisle.litislecontent.domain.dto.FeedQuery;
import com.litisle.litislecontent.domain.vo.BookFeedItemVO;
import com.litisle.litislecontent.domain.vo.FeedItemVO;
import com.litisle.litislecontent.domain.vo.FeedPageVO;

/**
 * 推荐流（无限刷）服务接口（批次十）
 * <p>文章流与书籍流独立，不做混合流；返回统一游标分页结构，
 * 分页细节（快照、续池、去重）由实现层收口，Controller 不再感知页码。</p>
 *
 * @author litisle
 */
public interface FeedStreamService {

    /**
     * 文章流：按游标持续供给可推荐文章
     *
     * @param query 查询入参（身份、游标、快照、页大小、分区筛选）
     * @return 文章流分页结果
     */
    FeedPageVO<FeedItemVO> feedArticles(FeedQuery query);

    /**
     * 书籍流：按游标持续供给可推荐书籍（不参与 zone，按 category 组织）
     *
     * @param query 查询入参（身份、游标、快照、页大小）
     * @return 书籍流分页结果
     */
    FeedPageVO<BookFeedItemVO> feedBooks(FeedQuery query);
}
