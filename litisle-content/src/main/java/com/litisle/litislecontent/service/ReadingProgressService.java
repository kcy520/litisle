package com.litisle.litislecontent.service;


/**
 * 阅读进度域服务接口——REST 由 ReadingProgressController 调用：
 *
 * 【核心机制（§6.6）】
 * - 参数合法性：chapter_id 必须归属上报的 book_id（book_chapter 反查，拒绝伪造跨书章节）
 * - 一致性：UPSERT 冲突转 update（uk_user_book）；仅前进不回退（新 chapter_no 小于当前值忽略）
 */
public interface ReadingProgressService {

    /**
     * 进度上报（服务端 @RateLimiter 兜底 + 前端 30s 节流；仅前进不回退）
     *
     * @param userId     读者ID
     * @param bookId     书籍ID
     * @param chapterId  最后阅读章节ID
     */
    void report(Long userId, Long bookId, Long chapterId);

    /**
     * 续读查询（取该用户在该书的最后阅读章节）
     *
     * @param userId 读者ID
     * @param bookId 书籍ID
     * @return 章节ID（无进度返回 null）
     */
    Long getLastChapter(Long userId, Long bookId);
}