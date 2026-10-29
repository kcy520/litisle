package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.Book;
import com.litisle.litislecontent.domain.entity.BookChapter;
import com.litisle.litislecontent.domain.entity.ReadingHistory;
import com.litisle.litislecontent.domain.vo.ReadingHistoryItemVO;
import com.litisle.litislecontent.domain.vo.ReadingHistoryVO;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.BookChapterMapper;
import com.litisle.litislecontent.mapper.BookMapper;
import com.litisle.litislecontent.mapper.ReadingHistoryMapper;
import com.litisle.litislecontent.service.ReadingHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 阅读历史域实现（单表双类型 UPSERT/仅前进/列表装配，§6.6 v2）。
 *
 * 作用：
 * 负责阅读历史的上报、自动落库与查询：report 为书籍阅读页显式上报（校验章节归属+仅前进），
 * recordFromArticle/recordBookRead 为阅读即落历史（最后一次阅读语义，回读旧章可回退）；
 * pageMyHistory/getLastRead/getLastChapter 供历史列表、书籍详情续读与续读接口使用；
 * 三条写入路径共用 upsertHistory（同一套 UPSERT 与唯一键冲突处理），靠 uk_user_biz 幂等。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReadingHistoryServiceImpl implements ReadingHistoryService {

    /** 阅读对象类型：书籍（biz_type=1，历史行含 chapter_id） */
    private static final int BIZ_TYPE_BOOK = 1;

    /** 阅读对象类型：独立文章（biz_type=2，历史行 chapter_id 为 null） */
    private static final int BIZ_TYPE_ARTICLE = 2;

    /** 阅读历史 Mapper（历史 UPSERT/列表/续读数据源） */
    private final ReadingHistoryMapper historyMapper;

    /** 章节（挂载）Mapper（章节归属校验/序号反查/归属判定数据源） */
    private final BookChapterMapper bookChapterMapper;

    /** 书籍 Mapper（列表书籍行信息批量装配） */
    private final BookMapper bookMapper;

    /** 文章 Mapper（列表文章行与章节标题批量装配） */
    private final ArticleMapper articleMapper;

    @Override
    public void report(Long userId, Long bookId, Long chapterId) {

        /**
         * 历史上报流程（仅前进不回退）：
         *
         * ① 章节归属校验：章节必须真的挂在这本书下（拒伪造跨书章节）；
         *    书被删了挂载行也会跟着删，章节查不到自然被拒。
         * ② 共用 UPSERT：无历史插入、有历史仅在新章节序号更大时更新；
         *    ≥30s 节流由 Controller 限频承接。
         */

        // 步骤1：chapter_id 归属 book_id 反查校验（拒绝伪造跨书章节）；书籍软删时挂载行已随删，
        // 章节行缺失自然走到本拒绝（无需额外 book 查询）
        BookChapter chapter = bookChapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getBookId().equals(bookId)) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_MISMATCH);
        }
        // 步骤2：共用 UPSERT（仅前进不回退，防多端乱序上报把进度拖回去）
        upsertHistory(userId, BIZ_TYPE_BOOK, bookId, chapterId, chapter, true);
    }

    @Override
    public Long getLastChapter(Long userId, Long bookId) {

        /**
         * 续读章节查询流程：
         *
         * ① 查书籍历史：没读过这本书返回 null（前端落到第一章）。
         * ② 兜底容错：历史指向的章节已被退章移除时，同样按无历史处理。
         */

        // 步骤1：查书籍历史（uk_user_biz 定位，固定 biz_type=1）
        ReadingHistory history = selectHistory(userId, BIZ_TYPE_BOOK, bookId);
        if (history == null || history.getChapterId() == null) {
            return null;
        }
        // 步骤2：历史指向的章节已被退章移除：按无历史返回 null，前端容错按最新章处理
        return bookChapterMapper.selectById(history.getChapterId()) == null
                ? null : history.getChapterId();
    }

    @Override
    public ReadingHistoryVO getLastRead(Long userId, Long bookId) {

        /**
         * 上次阅读详情查询流程（阶段17 书籍详情「上次读到第N章」）：
         *
         * ① 查书籍历史：无历史返回 null。
         * ② 章节行已删（退章/重排）→ 返回 null，调用方按无续读处理。
         * ③ 章节文章已删 → 返回 null。
         * 可见性不在本方法校验，由阶段17 调用方按书籍可见性 scope 复核。
         */

        // 步骤1：查书籍历史
        ReadingHistory history = selectHistory(userId, BIZ_TYPE_BOOK, bookId);
        if (history == null || history.getChapterId() == null) {
            return null;
        }
        // 步骤2：章节行已删 → 无续读
        BookChapter chapter = bookChapterMapper.selectById(history.getChapterId());
        if (chapter == null) {
            return null;
        }
        // 步骤3：章节文章已删 → 无续读
        Article article = articleMapper.selectById(chapter.getArticleId());
        if (article == null || article.getDelFlag() == DelFlag.DELETED) {
            return null;
        }
        // 步骤4：组装出参（章节标题取自章节文章 title）
        return new ReadingHistoryVO()
                .setBookId(bookId)
                .setChapterId(chapter.getId())
                .setChapterNo(chapter.getChapterNo())
                .setChapterTitle(article.getTitle())
                .setLastReadTime(history.getUpdateTime());
    }

    @Override
    public Page<ReadingHistoryItemVO> pageMyHistory(Long userId, Integer bizType, long pageNo, long pageSize) {

        /**
         * 阅读历史分页查询流程：
         *
         * ① SQL 层分页（自定义 join 已过滤软删对象，update_time desc + id desc 稳定倒序）；
         *    分页用 Page.of 裸构造，不走 PageQuery 默认排序（SQL 已显式排序，避免叠加）。
         * ② 按 biz_type 分组批量装配对象信息（in 查询防 N+1）：
         *    对象已软删的历史行静默跳过，不物理删历史行。
         * ③ 书籍行批量装配章节信息：章节行已删 → 章节三字段 null；章节在但文章已删 → 序号保留、标题 null；
         *    两种情况都不阻断书籍行展示（书名+最后阅读时间仍返回）。
         * ④ 组装出参并按 ① 的顺序重排（in 批查结果会乱序）。
         */

        // 步骤0：bizType 白名单校验（非法值直接拒绝）
        if (bizType != null && bizType != BIZ_TYPE_BOOK && bizType != BIZ_TYPE_ARTICLE) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤1：SQL 层分页查询（total 只统计可见行）
        Page<ReadingHistory> historyPage = historyMapper.selectMyHistoryPage(
                Page.of(pageNo, pageSize), userId, bizType);
        List<ReadingHistory> rows = historyPage.getRecords();
        Page<ReadingHistoryItemVO> result = new Page<>(
                historyPage.getCurrent(), historyPage.getSize(), historyPage.getTotal());
        if (rows.isEmpty()) {
            // 空历史返回空页（不抛异常）
            return result;
        }
        // 步骤2：书籍行对象信息批装（防御性过滤软删行）
        Map<Long, Book> bookMap = batchToMap(
                rows.stream()
                        .filter(r -> r.getBizType() == BIZ_TYPE_BOOK)
                        .map(ReadingHistory::getBizId)
                        .toList(),
                bookMapper::selectBatchIds, Book::getId);
        // 步骤3：文章行对象信息批装
        Map<Long, Article> articleMap = batchToMap(
                rows.stream()
                        .filter(r -> r.getBizType() == BIZ_TYPE_ARTICLE)
                        .map(ReadingHistory::getBizId)
                        .toList(),
                articleMapper::selectBatchIds, Article::getId);
        // 步骤4：书籍行章节信息批装（章节行 + 章节文章标题，仅 biz_type=1 行需要）
        Map<Long, BookChapter> chapterMap = batchToMap(
                rows.stream()
                        .filter(r -> r.getBizType() == BIZ_TYPE_BOOK && r.getChapterId() != null)
                        .map(ReadingHistory::getChapterId)
                        .toList(),
                bookChapterMapper::selectBatchIds, BookChapter::getId);
        Map<Long, Article> chapterArticleMap = batchToMap(
                chapterMap.values().stream()
                        .map(BookChapter::getArticleId)
                        .distinct()
                        .toList(),
                articleMapper::selectBatchIds, Article::getId);
        // 步骤5：组装出参并按步骤1 顺序重排
        List<ReadingHistoryItemVO> items = new ArrayList<>(rows.size());
        for (ReadingHistory row : rows) {
            if (row.getBizType() == BIZ_TYPE_BOOK) {
                Book book = bookMap.get(row.getBizId());
                if (book == null) {
                    // 对象已软删：静默跳过（历史行保留，不物理删）
                    continue;
                }
                BookChapter chapter = row.getChapterId() == null
                        ? null : chapterMap.get(row.getChapterId());
                Article chapterArticle = chapter == null
                        ? null : chapterArticleMap.get(chapter.getArticleId());
                items.add(new ReadingHistoryItemVO()
                        .setBizType(BIZ_TYPE_BOOK)
                        .setBizId(row.getBizId())
                        .setTitle(book.getTitle())
                        .setCoverFileId(book.getCoverFileId())
                        .setLastChapterId(chapter == null ? null : chapter.getId())
                        .setLastChapterNo(chapter == null ? null : chapter.getChapterNo())
                        .setLastChapterTitle(chapterArticle == null
                                || chapterArticle.getDelFlag() == DelFlag.DELETED
                                ? null : chapterArticle.getTitle())
                        .setLastReadTime(row.getUpdateTime()));
            } else {
                Article article = articleMap.get(row.getBizId());
                if (article == null) {
                    // 对象已软删：静默跳过
                    continue;
                }
                items.add(new ReadingHistoryItemVO()
                        .setBizType(BIZ_TYPE_ARTICLE)
                        .setBizId(row.getBizId())
                        .setTitle(article.getTitle())
                        .setCoverFileId(article.getCoverFileId())
                        .setLastReadTime(row.getUpdateTime()));
            }
        }
        result.setRecords(items);
        return result;
    }

    @Override
    public void recordFromArticle(Long userId, Long articleId) {

        /**
         * 阅读即落历史流程（文章详情命中，自动路径）：
         *
         * ① 反查挂载行（limit 2 覆盖 0/1/多书判定）；
         * ② 0 条 → 独立文章历史；1 条 → 书籍历史；多书 → 歧义跳过（交书籍阅读页 PUT 显式上报）；
         * ③ UPSERT 按「最后一次阅读」刷新（回读旧章可回退，不做仅前进校验）。
         * 极端乱序边界：延迟到达的 PUT 仍可能按「仅前进」覆盖自动回退结果，当前接受该边界。
         */

        // 步骤1：归属判定——一篇文章挂多本书时禁止「取最早挂载那条」猜书（会把历史记到错误书籍）
        List<BookChapter> mounts = bookChapterMapper.selectByArticleId(articleId);
        if (mounts.isEmpty()) {
            // 步骤2：独立文章 → biz_type=2，无章节
            upsertHistory(userId, BIZ_TYPE_ARTICLE, articleId, null, null, false);
        } else if (mounts.size() == 1) {
            // 步骤3：唯一挂载 → 落该书书籍历史（章节信息随行）
            BookChapter chapter = mounts.get(0);
            upsertHistory(userId, BIZ_TYPE_BOOK, chapter.getBookId(), chapter.getId(), chapter, false);
        } else {
            // 步骤4：多书挂载歧义 → 不自动落历史，debug 留痕
            log.debug("文章多书挂载，跳过自动落历史：articleId={}, mountCount={}", articleId, mounts.size());
        }
    }

    @Override
    public void recordBookRead(Long userId, Long bookId, Long chapterId) {

        /**
         * 章节阅读落书籍历史流程（阶段17 章节详情端点，显式 book 上下文）：
         *
         * ① 校验章节行存在且归属该书（不匹配抛 BOOK_CHAPTER_MISMATCH）；
         * ② UPSERT 按「最后一次阅读」刷新（update_time 刷新，不做仅前进校验）。
         */

        // 步骤1：归属校验
        BookChapter chapter = bookChapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getBookId().equals(bookId)) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_MISMATCH);
        }
        // 步骤2：UPSERT（最后一次阅读语义）
        upsertHistory(userId, BIZ_TYPE_BOOK, bookId, chapterId, chapter, false);
    }

    /**
     * 历史行定位查询（uk_user_biz 三段键 selectOne）
     *
     * @param userId  读者ID
     * @param bizType 阅读对象类型：1-书籍 2-独立文章
     * @param bizId   阅读对象ID
     * @return 历史行（无则 null）
     */
    private ReadingHistory selectHistory(Long userId, int bizType, Long bizId) {
        return historyMapper.selectOne(new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getUserId, userId)
                .eq(ReadingHistory::getBizType, bizType)
                .eq(ReadingHistory::getBizId, bizId));
    }

    /**
     * 历史 UPSERT（report / recordFromArticle / recordBookRead 三路径共用，防三份逻辑漂移）：
     * 无历史插入（两端并发首写撞 uk_user_biz 唯一键，捕获后转更新分支），
     * 有历史更新 chapter_id 并显式刷新 update_time。
     *
     * @param userId      读者ID
     * @param bizType     阅读对象类型：1-书籍 2-独立文章
     * @param bizId       阅读对象ID
     * @param chapterId   最后阅读章节ID（biz_type=2 传 null）
     * @param chapter     本次阅读的章节行（仅前进序号比对复用，免重复查；biz_type=2 传 null）
     * @param onlyForward true=仅前进不回退（report 显式上报，防多端乱序回退）；
     *                    false=最后一次阅读语义（自动落历史，回读旧章可回退）
     */
    private void upsertHistory(Long userId, int bizType, Long bizId,
                               Long chapterId, BookChapter chapter, boolean onlyForward) {
        ReadingHistory current = selectHistory(userId, bizType, bizId);
        if (current == null) {
            // 首次写入：插入；并发首写撞唯一索引 → 转查询走更新分支
            ReadingHistory history = new ReadingHistory()
                    .setUserId(userId)
                    .setBizType(bizType)
                    .setBizId(bizId)
                    .setChapterId(chapterId);
            try {
                historyMapper.insert(history);
                return;
            } catch (DuplicateKeyException e) {
                current = selectHistory(userId, bizType, bizId);
                if (current == null) {
                    // 极端并发（冲突行恰被删）：本次跳过，下次上报兜底
                    return;
                }
            }
        }
        if (onlyForward) {
            // 仅前进不回退：经章节表反查双方序号比对，新序号 ≤ 当前序号 → 多端乱序，忽略；
            // 当前章节行缺失（被退章重排）：无法比对，容错放行更新
            BookChapter lastChapter = current.getChapterId() == null
                    ? null : bookChapterMapper.selectById(current.getChapterId());
            if (lastChapter != null && chapter != null
                    && chapter.getChapterNo() <= lastChapter.getChapterNo()) {
                return;
            }
        }
        ReadingHistory upd = new ReadingHistory();
        upd.setId(current.getId());
        upd.setChapterId(chapterId);
        // 显式刷新最后阅读时间：DB 的 on update current_timestamp 仅在行值变化时触发，
        // 同章节/同文章重读无列变化，需显式 SET 才能刷新时间线位置
        upd.setUpdateTime(LocalDateTime.now());
        historyMapper.updateById(upd);
    }

    /**
     * 批量查询并转为 id→实体 映射（in 查询防 N+1；空 id 集合直接返回空映射，免打 DB）
     *
     * @param ids           业务主键集合
     * @param batchQueryer  批量查询函数（对应 Mapper#selectBatchIds）
     * @param keyExtractor  实体主键提取器（如 Book::getId）
     * @param <T>           实体类型
     * @return id→实体 映射
     */
    private <T> Map<Long, T> batchToMap(List<Long> ids,
                                        Function<List<Long>, List<T>> batchQueryer,
                                        Function<T, Long> keyExtractor) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return batchQueryer.apply(ids).stream()
                .collect(Collectors.toMap(keyExtractor, Function.identity()));
    }
}
