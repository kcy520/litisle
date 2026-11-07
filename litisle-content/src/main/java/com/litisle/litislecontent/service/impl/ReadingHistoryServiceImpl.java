package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislecontent.config.ContentProperties;
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
import com.litisle.litislecontent.enums.BookStatusEnum;
import com.litisle.litislecontent.enums.ReadingTargetTypeEnum;
import com.litisle.litislecontent.service.ReadingHistoryService;
import com.litisle.litislecontent.service.support.ContentStatSupport;
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

    /** 单批懒删行数默认值：正常每次写入只新增 1 行（超额至多几行），封顶 100 防异常数据导致一次大删；
     * 批次十一 11.4 起实际生效值取 litisle.content.history.trim-batch-size（yml 可调） */
    private static final int HISTORY_TRIM_BATCH = 100;

    /** 阅读历史 Mapper（历史 UPSERT/列表/续读数据源） */
    private final ReadingHistoryMapper historyMapper;

    /** 章节（挂载）Mapper（章节归属校验/序号反查/归属判定数据源） */
    private final BookChapterMapper bookChapterMapper;

    /** 书籍 Mapper（列表书籍行信息批量装配） */
    private final BookMapper bookMapper;

    /** 文章 Mapper（列表文章行与章节标题批量装配） */
    private final ArticleMapper articleMapper;

    /** 互动统计写入组件（批次十一 11.1：首次阅读书籍时 reader_count +1） */
    private final ContentStatSupport contentStatSupport;

    /** content 配置（批次十一 11.4：阅读历史裁剪时间窗/条数上限/在读保留三阈值） */
    private final ContentProperties contentProperties;

    /**
     * 书籍阅读进度显式上报（章节归属校验 + 仅前进 UPSERT）。
     *
     * @param userId    读者 ID
     * @param bookId    书籍 ID
     * @param chapterId 当前阅读章节 ID（必须挂在该书下）
     */
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
        // 步骤1+（Q5）：书状态校验——下架书非作者拒上报（读路径 requireReadableBook 已挡下架书，
        // 收口「读不了但能报进度」的不一致；作者本人放行=与读路径「下架书仅作者可读」同口径）。
        // book 为 null（章节校验后书被软删的极窄窗口）跳过校验不阻塞上报
        Book book = bookMapper.selectById(bookId);
        if (book != null && book.getStatus() == BookStatusEnum.TAKEN_DOWN
                && !book.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.BOOK_STATUS_INVALID);
        }
        // 步骤2：共用 UPSERT（仅前进不回退，防多端乱序上报把进度拖回去）
        upsertHistory(userId, ReadingTargetTypeEnum.BOOK, bookId, chapterId, chapter, true);
    }

    /**
     * 查询书籍续读章节（无历史或历史章节已移除时返回 null，前端落到第一章）。
     *
     * @param userId 读者 ID
     * @param bookId 书籍 ID
     * @return 最后阅读章节 ID；无有效续读返回 null
     */
    @Override
    public Long getLastChapter(Long userId, Long bookId) {

        /**
         * 续读章节查询流程：
         *
         * ① 查书籍历史：没读过这本书返回 null（前端落到第一章）。
         * ② 兜底容错：历史指向的章节已被退章移除时，同样按无历史处理。
         */

        // 步骤1：查书籍历史（uk_user_biz 定位，固定书籍类型）
        ReadingHistory history = selectHistory(userId, ReadingTargetTypeEnum.BOOK, bookId);
        if (history == null || history.getChapterId() == null) {
            return null;
        }
        // 步骤2：历史指向的章节已被退章移除：按无历史返回 null，前端容错按最新章处理
        return bookChapterMapper.selectById(history.getChapterId()) == null
                ? null : history.getChapterId();
    }

    /**
     * 查询书籍「上次阅读」详情（书名详情页展示最后章节号/标题/阅读时间）。
     *
     * @param userId 读者 ID
     * @param bookId 书籍 ID
     * @return 上次阅读详情；无历史、章节已删或章节文章已删时返回 null
     */
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
        ReadingHistory history = selectHistory(userId, ReadingTargetTypeEnum.BOOK, bookId);
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

    /**
     * 我的阅读历史分页查询（bizType 可选 1-书籍 / 2-独立文章，对象信息 in 批装防 N+1）。
     *
     * @param userId   读者 ID
     * @param bizType  阅读对象类型筛选（null=全部；非法值抛 PARAM_ERROR）
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 阅读历史分页结果（对象已软删的历史行静默跳过，不物理删历史行）
     */
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

        // 步骤0：bizType 白名单校验（请求侧 Integer 统一 resolve；非法值直接拒绝）
        ReadingTargetTypeEnum targetType = ReadingTargetTypeEnum.resolve(bizType);
        if (bizType != null && targetType == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤1：SQL 层分页查询（total 只统计可见行；Mapper 入参统一传入库编码）
        Page<ReadingHistory> historyPage = historyMapper.selectMyHistoryPage(
                Page.of(pageNo, pageSize), userId,
                targetType == null ? null : targetType.getCode());
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
                        .filter(r -> ReadingTargetTypeEnum.BOOK == r.getBizType())
                        .map(ReadingHistory::getBizId)
                        .toList(),
                bookMapper::selectBatchIds, Book::getId);
        // 步骤3：文章行对象信息批装
        Map<Long, Article> articleMap = batchToMap(
                rows.stream()
                        .filter(r -> ReadingTargetTypeEnum.ARTICLE == r.getBizType())
                        .map(ReadingHistory::getBizId)
                        .toList(),
                articleMapper::selectBatchIds, Article::getId);
        // 步骤4：书籍行章节信息批装（章节行 + 章节文章标题，仅书籍类型行需要）
        Map<Long, BookChapter> chapterMap = batchToMap(
                rows.stream()
                        .filter(r -> ReadingTargetTypeEnum.BOOK == r.getBizType() && r.getChapterId() != null)
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
            if (ReadingTargetTypeEnum.BOOK == row.getBizType()) {
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
                        .setBizType(ReadingTargetTypeEnum.BOOK.getCode())
                        .setBizTypeDesc(row.getBizType() == null ? null : row.getBizType().getDesc())
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
                        .setBizType(ReadingTargetTypeEnum.ARTICLE.getCode())
                        .setBizTypeDesc(row.getBizType() == null ? null : row.getBizType().getDesc())
                        .setBizId(row.getBizId())
                        .setTitle(article.getTitle())
                        .setCoverFileId(article.getCoverFileId())
                        .setLastReadTime(row.getUpdateTime()));
            }
        }
        result.setRecords(items);
        return result;
    }

    /**
     * 文章详情命中后自动落历史（归属判定：0 挂载=独立文章、1 挂载=书籍章节、多挂载跳过交显式上报）。
     *
     * @param userId    读者 ID
     * @param articleId 文章 ID
     */
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
            // 步骤2：独立文章 → 文章类型历史，无章节
            upsertHistory(userId, ReadingTargetTypeEnum.ARTICLE, articleId, null, null, false);
        } else if (mounts.size() == 1) {
            // 步骤3：唯一挂载 → 落该书书籍历史（章节信息随行）
            BookChapter chapter = mounts.get(0);
            upsertHistory(userId, ReadingTargetTypeEnum.BOOK, chapter.getBookId(), chapter.getId(), chapter, false);
        } else {
            // 步骤4：多书挂载歧义 → 不自动落历史，debug 留痕
            log.debug("文章多书挂载，跳过自动落历史：articleId={}, mountCount={}", articleId, mounts.size());
        }
    }

    /**
     * 章节阅读页自动落书籍历史（显式 book 上下文，校验章节归属后按最后一次阅读 UPSERT）。
     *
     * @param userId    读者 ID
     * @param bookId    书籍 ID
     * @param chapterId 章节 ID
     */
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
        upsertHistory(userId, ReadingTargetTypeEnum.BOOK, bookId, chapterId, chapter, false);
    }

    /**
     * 历史行定位查询（uk_user_biz 三段键 selectOne）
     *
     * @param userId  读者ID
     * @param bizType 阅读对象类型（书籍/独立文章）
     * @param bizId   阅读对象ID
     * @return 历史行（无则 null）
     */
    private ReadingHistory selectHistory(Long userId, ReadingTargetTypeEnum bizType, Long bizId) {
        return historyMapper.selectOne(new LambdaQueryWrapper<ReadingHistory>()
                .eq(ReadingHistory::getUserId, userId)
                // 条件值统一传入库编码，交由普通 Integer 参数绑定（@EnumValue 仅用于实体字段映射）
                .eq(ReadingHistory::getBizType, bizType.getCode())
                .eq(ReadingHistory::getBizId, bizId));
    }


    /**
     * 历史 UPSERT（report / recordFromArticle / recordBookRead 三路径共用，防三份逻辑漂移）：
     * 无历史插入（两端并发首写撞 uk_user_biz 唯一键，捕获后转更新分支），
     * 有历史更新 chapter_id 并显式刷新 update_time。
     *
     * @param userId      读者ID
     * @param bizType     阅读对象类型（书籍/独立文章）
     * @param bizId       阅读对象ID
     * @param chapterId   最后阅读章节ID（独立文章传 null）
     * @param chapter     本次阅读的章节行（仅前进序号比对复用，免重复查；独立文章传 null）
     * @param onlyForward true=仅前进不回退（report 显式上报，防多端乱序回退）；
     *                    false=最后一次阅读语义（自动落历史，回读旧章可回退）
     */
    private void upsertHistory(Long userId, ReadingTargetTypeEnum bizType, Long bizId,
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
                // 首次阅读这本书：阅读人数 +1。并发首写时唯一键 uk_user_biz 只允许一条插入成功，
                // 其余线程走下面的 DuplicateKey 分支，因此这里天然不会重复计数
                if (bizType == ReadingTargetTypeEnum.BOOK) {
                    try {
                        contentStatSupport.increaseReader(bizId);
                    } catch (Exception e) {
                        // 阅读人数属非账务热度信号：失败只告警、不阻断阅读上报，由 11.16 对账发现少计
                        log.warn("[阅读统计] 阅读人数 +1 失败，已跳过：userId={}, bookId={}", userId, bizId, e);
                    }
                }
                // 新增一行后懒删过期/超额行（批次十一 11.4：90 天时间窗 + 单用户 1000 条 + 在读保留 10 条）
                trimOverflowHistory(userId, bizType);
                return;
            } catch (DuplicateKeyException e) {
                current = selectHistory(userId, bizType, bizId);
                if (current == null) {
                    // 极端并发（冲突行恰被删）：本次跳过，下次上报兜底
                    return;
                }
            }
        }

        //当前此条历史记录，如果上述插入成功，会直接返回return，如果没有插入成功，说明上述阅读历史存在，在此处做当前词条历史记录的更新处理
        if (onlyForward) {
            // 仅前进不回退：经章节表反查双方序号比对，新序号 ≤ 当前序号 → 多端乱序，忽略；
            // 当前章节行缺失（被退章重排）：无法比对，容错放行更新
            // 文章没有章节id，文章的章节id直接返回null，不需要考虑章节前进与否
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
     * 阅读历史裁剪（批次十一 11.4：90 天时间窗 + 单用户 1000 条 + 在读保留 10 条，三阈值配置化）
     *
     * <p>删除该用户该类型下的行，同时满足三个豁免条件之一就保留：</p>
     * <ol>
     *   <li>位于「按最后阅读时间倒序」的前 max-per-user 条（条数上限豁免）；</li>
     *   <li>属于最近 keep-in-progress 条「在读」书籍历史（在读豁免）；</li>
     *   <li>其余行中 update_time 在 retain-days 天内的也保留（时间窗）。</li>
     * </ol>
     *
     * <p>即实际删除集 = 非在读豁免 ∧（超出条数上限 ∨ 早于时间窗）。只在新插入行后调用
     * （更新行不新增条数，无需触发）；懒删意味着用户停止阅读后过期行不会被主动清理，
     * 下次写入时一并回收，属已知滞后。</p>
     *
     * <p>在读判定（不新增埋点，仅用现有字段）：取该书在 book_chapter 的最大可见章节号，
     * 历史最后阅读章节号小于它即视为未读完；章节行缺失/无 chapter_id/书籍无可见章节时无法判断，
     * 保守按在读保留。局限：读者读完最后一章后书又新增章节，会重新变为在读（可接受）。</p>
     *
     * <p>fail-safe：删除失败仅 warn，不影响本次落历史（下次写入继续兜底清理）。</p>
     *
     * @param userId  读者ID
     * @param bizType 阅读对象类型（书籍/独立文章；在读豁免仅对书籍类型生效）
     */
    private void trimOverflowHistory(Long userId, ReadingTargetTypeEnum bizType) {
        // 裁剪配置（时间窗/条数上限/在读保留/单批上限全部外置，改参数不发版）
        ContentProperties.History config = contentProperties.getHistory();
        // 开关关闭：不产生删除（reading_history 只增不减）
        if (!config.isTrimEnabled()) {
            return;
        }
        try {
            // 时间窗起点：早于该时刻的行进入删除候选（min 1 天防配置 0 把全部历史清空）
            LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(config.getRetainDays(), 1));
            // 在读豁免仅对书籍类型生效：文章没有章节进度概念，传 0 让 LIMIT 0 返回空集
            int keepInProgress = bizType == ReadingTargetTypeEnum.BOOK ? config.getKeepInProgress() : 0;
            // 单批删除：条数上限 + 在读保留 + 单批封顶，全部取配置值
            historyMapper.deleteOverflowOldest(userId, bizType.getCode(), cutoff,
                    config.getMaxPerUser(), keepInProgress, config.getTrimBatchSize());
        } catch (Exception e) {
            // 懒删非核心链路：失败不影响本次落历史，下轮写入继续清理
            log.warn("阅读历史裁剪失败（fail-safe 不影响本次落历史）：userId={}, bizType={}",
                    userId, bizType, e);
        }
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
