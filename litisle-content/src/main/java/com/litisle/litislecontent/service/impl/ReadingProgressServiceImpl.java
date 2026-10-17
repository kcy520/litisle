package com.litisle.litislecontent.service.impl;


import com.litisle.litislecontent.service.ReadingProgressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 阅读进度域实现（UPSERT/仅前进不回退，§6.6）。
 */
@Service
@Slf4j
public class ReadingProgressServiceImpl implements ReadingProgressService {

    /**
     * 上报阅读进度（§6.6）：chapter 归属 book 反查校验（拒伪造跨书章节）→ UPSERT
     * （冲突转 update；新 chapter_no 小于当前值忽略，防多端乱序回退）；≥30s 节流由 Controller 限频承接
     *
     * @param userId    当前登录用户ID
     * @param bookId    书籍ID
     * @param chapterId 章节ID
     */
    @Override
    public void report(Long userId, Long bookId, Long chapterId) {
        // TODO chapter_id 归属 book_id 反查校验（拒绝伪造跨书章节）
        // TODO UPSERT 冲突转 update；新 chapter_no 小于当前值忽略（防多端乱序回退）
    }

    /**
     * 查询续读章节（无进度返回 null，前端落到第一章）
     *
     * @param userId 当前登录用户ID
     * @param bookId 书籍ID
     * @return 最近阅读章节ID（无进度为 null）
     */
    @Override
    public Long getLastChapter(Long userId, Long bookId) {
        // TODO 无进度返回 null
        return null;
    }
}