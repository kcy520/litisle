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
 * 
 * 作用：
 * 负责阅读进度的上报与续读章节查询：上报时校验章节归属书籍（拒伪造跨书章节），
 * UPSERT 写入进度并仅允许向前推进（防多端乱序回退）；查询返回最近阅读章节ID，
 * 保证多端并发下的进度一致性。
 * 
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReadingProgressServiceImpl implements ReadingProgressService {

    /** 阅读进度 Mapper（进度 UPSERT/续读查询数据源） */
    private final ReadingProgressMapper progressMapper;

    /** 章节（挂载）Mapper（章节归属校验/序号反查数据源） */
    private final BookChapterMapper bookChapterMapper;

        
    /**
     * 上报阅读进度。
     *
     * 作用：
     * 校验章节归属书籍（拒伪造跨书章节）后写入阅读进度：无进度则插入（两端同时首次上报
     * 会撞数据库唯一索引，捕获后转更新分支）；有进度则仅在新章节序号大于当前值时
     * 更新（防多端乱序回退）。≥30s 节流由 Controller 限频承接。
     *
     * @param userId    当前登录用户ID
     * @param bookId    书籍ID
     * @param chapterId 章节ID（须归属 bookId）
     */
    @Override
    public void report(Long userId, Long bookId, Long chapterId) {

        /**
         * 上报阅读进度流程：
         *
         * ① 章节归属校验：章节必须真的挂在这本书下（拒伪造跨书章节）；
         *    书被删了挂载行也会跟着删，章节查不到自然被拒。
         * ② 查当前进度：
         *    - 首次上报：插入新进度行；两端同时首次上报由数据库唯一索引兜底转更新；
         *    - 极端并发（刚插入的行恰好被删）：本次跳过，下次上报兑底。
         * ③ 仅前进不回退：反查新旧章节的序号比对，新章节序号不大于已读序号时忽略
         *    （防多端乱序上报把进度拖回去）；旧章节行已被移除时无法比对，容错放行。
         * ④ 更新进度到新章节。
         */

        // 步骤1：chapter_id 归属 book_id 反查校验（拒绝伪造跨书章节）；书籍软删时挂载行已随删，
        // 章节行缺失自然走到本拒绝（无需额外 book 查询）
        BookChapter chapter = bookChapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getBookId().equals(bookId)) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_MISMATCH);
        }
        // 步骤2：查询当前用户在该书的进度
        ReadingProgress current = progressMapper.selectOne(new LambdaQueryWrapper<ReadingProgress>()
                .eq(ReadingProgress::getUserId, userId)
                .eq(ReadingProgress::getBookId, bookId));
        if (current == null) {
            // 首次上报：插入；两端同时首次上报会撞数据库唯一索引，捕获后转为查询再走更新分支
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
        // 步骤3：仅前进不回退：进度表没有章节序号列，经章节表反查双方序号比对；
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
     * 从MySQL查询阅读进度并返回最近阅读章节ID
     * 
     * @param userId 当前登录用户ID
     * @param bookId 书籍ID
     * @return 最近阅读章节ID（无进度为 null）
     */
    @Override
    public Long getLastChapter(Long userId, Long bookId) {

        /**
         * 续读章节查询流程：
         *
         * ① 查阅读进度：没读过这本书返回 null（前端落到第一章）。
         * ② 兑底容错：进度指向的章节已被退章移除时，同样按无进度处理。
         */

        // 步骤1：查询阅读进度
        ReadingProgress progress = progressMapper.selectOne(new LambdaQueryWrapper<ReadingProgress>()
                .eq(ReadingProgress::getUserId, userId)
                .eq(ReadingProgress::getBookId, bookId));
        // 步骤2：非空判断
        if (progress == null) {
            return null;
        }
        // 步骤3：进度指向的章节已被退章移除：按无进度返回 null，前端容错按最新章处理
        return bookChapterMapper.selectById(progress.getChapterId()) == null
                ? null : progress.getChapterId();
    }
}