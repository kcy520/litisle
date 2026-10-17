package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislecontent.domain.entity.BookChapter;
import com.litisle.litislecontent.domain.entity.ReadingProgress;
import com.litisle.litislecontent.mapper.BookChapterMapper;
import com.litisle.litislecontent.mapper.ReadingProgressMapper;
import com.litisle.litislecontent.service.ReadingProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 阅读进度域实现（UPSERT/仅前进不回退，§6.6）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReadingProgressServiceImpl implements ReadingProgressService {

    private final ReadingProgressMapper progressMapper;

    private final BookChapterMapper bookChapterMapper;

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
        // chapter_id 归属 book_id 反查校验（拒绝伪造跨书章节）；书籍软删时挂载行已随删，
        // 章节行缺失自然走到本拒绝（无需额外 book 查询）
        BookChapter chapter = bookChapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getBookId().equals(bookId)) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_MISMATCH);
        }
        ReadingProgress current = progressMapper.selectOne(new LambdaQueryWrapper<ReadingProgress>()
                .eq(ReadingProgress::getUserId, userId)
                .eq(ReadingProgress::getBookId, bookId));
        if (current == null) {
            // 首次上报：insert；并发双端同时首报由 uk_user_book 兑底转 update 分支
            ReadingProgress progress = new ReadingProgress()
                    .setUserId(userId)
                    .setBookId(bookId)
                    .setChapterId(chapterId);
            try {
                progressMapper.insert(progress);
                return;
            } catch (DuplicateKeyException e) {
                current = progressMapper.selectOne(new LambdaQueryWrapper<ReadingProgress>()
                        .eq(ReadingProgress::getUserId, userId)
                        .eq(ReadingProgress::getBookId, bookId));
                if (current == null) {
                    // 极端并发（冲突行恰被删）：本次跳过，下次上报兜底
                    return;
                }
            }
        }
        // 仅前进不回退：reading_progress 无 chapter_no 列，经 book_chapter 反查双方序号比对；
        // 当前章节行缺失（被退章重排）：无法比对，容错放行更新；新序号 ≤ 当前序号：多端乱序，忽略
        BookChapter lastChapter = bookChapterMapper.selectById(current.getChapterId());
        if (lastChapter != null && chapter.getChapterNo() <= lastChapter.getChapterNo()) {
            return;
        }
        ReadingProgress upd = new ReadingProgress();
        upd.setId(current.getId());
        upd.setChapterId(chapterId);
        progressMapper.updateById(upd);
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
        ReadingProgress progress = progressMapper.selectOne(new LambdaQueryWrapper<ReadingProgress>()
                .eq(ReadingProgress::getUserId, userId)
                .eq(ReadingProgress::getBookId, bookId));
        if (progress == null) {
            return null;
        }
        // 进度指向的章节已被退章移除：按无进度返回 null，前端容错按最新章处理
        return bookChapterMapper.selectById(progress.getChapterId()) == null
                ? null : progress.getChapterId();
    }
}