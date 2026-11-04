package com.litisle.litislesearch.service;

/**
 * 书籍索引同步服务（批次五 B4）。
 *
 * 作用：消费 content.book-changed 事件后，回查书籍权威数据并维护 book_idx：
 * 满足入库门控则 upsert，书籍不存在/已软删/已下架则删除索引文档。
 *
 * @author litisle
 */
public interface BookIndexService {

    /**
     * 处理书籍变更事件（创建/编辑/完结/下架/删除统一入口）。
     *
     * @param bookId 书籍 ID
     */
    void handleChanged(Long bookId);
}
