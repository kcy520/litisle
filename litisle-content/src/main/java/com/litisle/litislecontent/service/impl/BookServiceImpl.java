package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.constant.PageConstant;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.PageResult;
import com.litisle.litislecontent.cache.FollowRelationCache;
import com.litisle.litislecontent.constant.ContentLimitConstant;
import com.litisle.litislecontent.domain.dto.BookChapterReorderDTO;
import com.litisle.litislecontent.domain.dto.BookMyQueryDTO;
import com.litisle.litislecontent.domain.dto.BookQueryDTO;
import com.litisle.litislecontent.domain.dto.BookSaveDTO;
import com.litisle.litislecontent.domain.dto.ChapterAddDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.Book;
import com.litisle.litislecontent.domain.entity.BookChapter;
import com.litisle.litislecontent.domain.vo.ArticleDetailVO;
import com.litisle.litislecontent.domain.vo.BookCardVO;
import com.litisle.litislecontent.domain.vo.BookChapterDetailVO;
import com.litisle.litislecontent.domain.vo.BookChapterVO;
import com.litisle.litislecontent.domain.vo.BookDetailVO;
import com.litisle.litislecontent.domain.vo.ReadingHistoryVO;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.BookStatusEnum;
import com.litisle.litislecontent.enums.FavoriteTargetTypeEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.service.support.ArticleImageRefSupport;
import com.litisle.litislecontent.service.support.ContentStatSupport;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mapper.BookChapterMapper;
import com.litisle.litislecontent.mapper.BookMapper;
import com.litisle.litislecontent.service.ArticleService;
import com.litisle.litislecontent.service.BookService;
import com.litisle.litislecontent.service.ReadingHistoryService;
import com.litisle.litislecontent.service.support.FavoriteStateSupport;
import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.litisleapi.event.content.ContentBookChangedEvent;
import com.litisle.litislecontent.mq.ContentEventProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 书籍/合集域实现（owner 管理 + 章节归属校验）。
 *
 * 作用：
 * 提供书籍（合集）的创建/编辑/软删与章节挂载管理。所有写操作先校验书籍归属本人
 * （软删书按不存在处理）；章节挂载要求文章为本人已发布未软删，且章节序号在书内
 * 唯一（先预检查重 + 数据库唯一索引兜底）；删除书籍仅软删书籍并清挂载关系，文章本体不受影响。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    /** 书籍 Mapper（书籍 CRUD 数据源） */
    private final BookMapper bookMapper;

    /** 章节（挂载关系）Mapper（章节挂载/冲突预检/移除数据源） */
    private final BookChapterMapper bookChapterMapper;

    /** 文章 Mapper（章节挂载时的文章归属/状态校验数据源） */
    private final ArticleMapper articleMapper;

    /** 文章服务（章节详情正文复用文章可见性判定链与渲染，阶段17） */
    private final ArticleService articleService;

    /** 阅读历史服务（书籍详情「上次阅读」回填数据源，阶段17） */
    private final ReadingHistoryService readingHistoryService;

    /** 收藏状态查询组件（书籍详情 favorited 回填，阶段17） */
    private final FavoriteStateSupport favoriteStateSupport;

    /** 关注关系缓存（章节可见性范围计算时回查关注者权限，social 不可用降级，阶段17） */
    private final FollowRelationCache followRelationCache;

    /** 文件引用支撑组件（Q3：书籍封面复用文章侧 checkCoverOwnership 归属+bizType 校验） */
    private final ArticleImageRefSupport articleImageRefSupport;

    /** 分布式锁（章节重排 book 粒度并发防护，批次5 2026-11-02） */
    private final DistributedLock distributedLock;

    /** content 域 MQ 事件生产收口（批次五 B4：书籍索引变更事件，事务提交后发送） */
    private final ContentEventProducer contentEventProducer;

    /** 互动统计写入组件（批次十一 11.2：挂章/移除章节同步 book_stat.chapter_count） */
    private final ContentStatSupport contentStatSupport;

    /** 分类缺省值（BookSaveDTO.category 可空，落库默认「小说」） */
    private static final String DEFAULT_CATEGORY = "小说";

    /**
     * 创建书籍/合集（§5.1：默认 status=0 连载中）
     *
     * @param dto    书籍入参（书名/简介/封面等）
     * @param userId 当前登录用户ID（作者归属）
     * @return 新建书籍ID
     */
    @Override
    public Long create(BookSaveDTO dto, Long userId) {

        /**
         * 创建书籍流程：
         *
         * ① 组装实体：归属作者本人；分类没填默认「小说」；状态默认「连载中」；未软删。
         * ② 落库：插入数据库拿到书籍ID返回。
         */

        // 步骤0（Q3）：封面归属校验（与文章封面同口径：归属本人 + bizType=ARTICLE_IMG，
        // 防盗图/引用被拒违规图 fileId）；无封面（null/空）直接放行
        articleImageRefSupport.checkCoverOwnership(dto.getCoverFileId(), userId);
        // 步骤1：组装实体（归属作者；分类缺省「小说」；状态默认连载中；未软删）
        Book book = new Book()
                .setAuthorId(userId)
                .setTitle(dto.getTitle())
                .setIntro(dto.getIntro())
                .setCoverFileId(dto.getCoverFileId())
                .setCategory(dto.getCategory() == null || dto.getCategory().isBlank()
                        ? DEFAULT_CATEGORY : dto.getCategory())
                .setStatus(BookStatusEnum.SERIALING)
                .setDelFlag(DelFlag.NOT_DELETED);
        // 步骤2：落库并返回主键
        bookMapper.insert(book);
        // 步骤3（批次五 B4）：发布书籍索引变更事件（create 无事务包裹，insert 即提交，可立即发送）
        publishBookChangedAfterCommit(book.getId());
        return book.getId();
    }

    /**
     * 编辑书籍（owner 校验，非本人拒绝）
     *
     * @param id     书籍ID
     * @param dto    编辑入参（非 null 字段生效）
     * @param userId 当前登录用户ID
     */
    @Override
    public void update(Long id, BookSaveDTO dto, Long userId) {

        /**
         * 编辑书籍流程：
         *
         * ① 归属校验：仅书籍作者本人可编辑（软删书按不存在处理，不暴露存在性）。
         * ② 增量更新：传了哪个字段就改哪个（null=不修改），组装后统一提交。
         */

        // 步骤1：owner 校验：非本人拒绝（软删书按不存在处理，不暴露存在性）
        Book book = requireOwnedBook(id, userId);
        // 步骤1+（Q4）：下架书拒改元数据（状态一致性收口，与 Q2 addChapter 拒挂同构）：
        // 下架=对外终止态，owner 在下架期间不可替换 title/intro/cover/category，
        // 防未来恢复路径上元数据已被调包
        if (book.getStatus() == BookStatusEnum.TAKEN_DOWN) {
            throw new BizException(ErrorCode.BOOK_STATUS_INVALID);
        }
        // 步骤2：非 null 字段更新（BookSaveDTO 全字段可空，null=不修改）
        Book upd = new Book();
        upd.setId(id);
        if (dto.getTitle() != null) {
            upd.setTitle(dto.getTitle());
        }
        if (dto.getIntro() != null) {
            upd.setIntro(dto.getIntro());
        }
        if (dto.getCoverFileId() != null) {
            // Q3：封面归属校验（与文章封面同口径：归属本人 + bizType=ARTICLE_IMG，防盗图/违规图绕审展示面）
            articleImageRefSupport.checkCoverOwnership(dto.getCoverFileId(), userId);
            upd.setCoverFileId(dto.getCoverFileId());
        }
        if (dto.getCategory() != null) {
            upd.setCategory(dto.getCategory());
        }
        bookMapper.updateById(upd);
        // 步骤3（批次五 B4）：发布书籍索引变更事件（update 无事务包裹，updateById 即提交，可立即发送）
        publishBookChangedAfterCommit(id);
    }

    /**
     * 删除书籍（owner 校验 + 软删，章节挂载关系随书籍失效）
     *
     * @param id     书籍ID
     * @param userId 当前登录用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId) {

        /**
         * 删除书籍流程：
         *
         * ① 归属校验：仅书籍作者本人可删。
         * ② 软删书籍：带条件改删除标记（并发重复删安全）；同一事务内物理清空章节挂载行。
         *    注意：文章本体不受影响，只是从书里“摘出来”；
         *    指向已移除章节的阅读历史行不联动处理：book 软删后阅读历史列表静默跳过该书历史行
         *    （SQL join 过滤，历史行保留，审计/恢复预留）。
         */

        // 步骤1：owner 校验
        requireOwnedBook(id, userId);
        // 步骤2：软删书籍（只改"未删除"状态的行，并发重复删安全）；章节挂载关系随书籍失效，物理清挂载行
        // （挂载关系无独立业务价值，文章本体不动）；阅读历史行不联动处理，
        // book 软删后阅读历史列表静默跳过该书历史行（历史行保留，不物理删）
        bookMapper.update(null, new LambdaUpdateWrapper<Book>()
                .eq(Book::getId, id)
                .eq(Book::getDelFlag, DelFlag.NOT_DELETED)
                .set(Book::getDelFlag, DelFlag.DELETED));
        bookChapterMapper.delete(new LambdaQueryWrapper<BookChapter>()
                .eq(BookChapter::getBookId, id));
        // 步骤3（批次五 B4）：发布书籍索引变更事件——本方法有事务，注册到 afterCommit 再发送，
        // 防止事务回滚后事件已飞出；消费端回查不到书籍（已软删）会自动删除 book_idx 文档
        publishBookChangedAfterCommit(id);
    }

    /**
     * 书籍索引变更事件发布（批次五 B4，BookServiceImpl 内部统一收口）。
     *
     * 作用：书籍创建/编辑/删除后通知 search 同步 book_idx；事件体只带 bookId，
     * 消费端回查书籍权威态后 upsert 或删除索引文档（完结状态复用同一条链路）。
     *
     * 【发送时机】有事务时注册到 afterCommit（防回滚产生脏事件）；无事务时直接发送。
     * 【失败语义】发送失败仅告警，不阻断书籍主流程（search 索引可重同步/重建兜底）。
     *
     * @param bookId 书籍 ID
     */
    private void publishBookChangedAfterCommit(Long bookId) {
        // 入参兜底：bookId 为空直接跳过（正常流程不会出现）
        if (bookId == null) {
            return;
        }
        // 组装事件体（基座字段由 ContentEventProducer.fillBase 统一填充）
        ContentBookChangedEvent event = new ContentBookChangedEvent();
        event.setBookId(bookId);
        // 发送动作：异常内部吞掉仅告警，不影响书籍写操作返回
        Runnable publisher = () -> {
            try {
                contentEventProducer.sendBookChanged(event);
            } catch (Exception e) {
                log.warn("书籍索引变更事件发送失败（不阻断书籍写操作），bookId={}", bookId, e);
            }
        };
        // 存在活动事务 → 注册 afterCommit；否则（create/update 无事务）立即发送
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 事务提交成功后触发索引事件发送
                 */
                @Override
                public void afterCommit() {
                    publisher.run();
                }
            });
        } else {
            publisher.run();
        }
    }

    /**
     * 添加章节（§5.1：文章归属本人校验 + uk(book_id, chapter_no) 冲突拒绝）
     *
     * @param bookId  书籍ID
     * @param dto     章节入参（文章ID/章节序号等）
     * @param userId  当前登录用户ID
     */
    @Override
    public void addChapter(Long bookId, ChapterAddDTO dto, Long userId) {

        /**
         * 添加章节流程：
         *
         * ① 书籍归属校验：只有书的作者能挂章节。
         * ② 文章校验：必须是本人、已发布、未删除的文章（防挂他人文章/未过审内容）。
         * ③ 章节序号预检：同一本书内序号不能重复（序号即阅读顺序）。
         * ④ 落挂载行：并发下两人同时挂同一序号时，由数据库唯一索引兜底转冲突报错。
         */

        // 步骤1：书籍归属校验（复用返回值，避免二次查询）
        Book book = requireOwnedBook(bookId, userId);
        // 步骤1+（Q2）：书籍状态校验——下架书（TAKEN_DOWN）拒挂新章节（状态一致性收口：
        // 下架=对外终止态，禁止继续扩章节；软删书已在 requireOwnedBook 按 BOOK_NOT_FOUND 拒绝，不重复校验）
        if (book.getStatus() == BookStatusEnum.TAKEN_DOWN) {
            throw new BizException(ErrorCode.BOOK_STATUS_INVALID);
        }
        // 步骤2：文章归属本人校验（防挂他人文章）：须为本人已发布未软删文章
        Article article = articleMapper.selectById(dto.getArticleId());
        if (article == null || article.getDelFlag() == DelFlag.DELETED) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        if (!article.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        if (article.getStatus() != ArticleStatusEnum.PUBLISHED) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 步骤3：uk(book_id, chapter_no) 冲突预检（序号即排序键不重复）
        Long conflict = bookChapterMapper.selectCount(new LambdaQueryWrapper<BookChapter>()
                .eq(BookChapter::getBookId, bookId)
                .eq(BookChapter::getChapterNo, dto.getChapterNo()));
        if (conflict > 0) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_NO_CONFLICT);
        }
        // 步骤4：落挂载行；并发下两人同时挂同一序号，由数据库唯一索引兜底转冲突报错
        BookChapter chapter = new BookChapter()
                .setBookId(bookId)
                .setArticleId(dto.getArticleId())
                .setChapterNo(dto.getChapterNo());
        try {
            bookChapterMapper.insert(chapter);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_NO_CONFLICT);
        }
        // 步骤5（批次十一 11.2 + 11.3 口径修正）：只有「公开可见」章节才 +1。
        // 可见口径 = 未删 + 已发布 + 公开（visibility=1）；私密(2)/仅关注者(3) 不计入卡片章节数，
        // 与回填脚本的可见章节口径保持一致（步骤2 已校验未删+已发布，此处只需再判公开）。
        // 已知滞后：文章挂章后再转私密/下架不会回写本计数，本批不自动重算，
        // 差异由人工重跑 zz-content-stat.sql 回填或后续对账扩展修正。
        // 章节数是展示型计数，同步失败只告警，不回滚已成功的挂载行
        if (article.getVisibility() == VisibilityEnum.PUBLIC) {
            try {
                contentStatSupport.increaseChapter(bookId);
            } catch (Exception e) {
                log.warn("[章节统计] 章节数 +1 失败，已跳过：bookId={}", bookId, e);
            }
        } else {
            log.info("[章节统计] 章节文章非公开，跳过章节数 +1：bookId={}, articleId={}, visibility={}",
                    bookId, article.getId(), article.getVisibility());
        }
    }

    /**
     * 移除章节（owner 校验，仅移除挂载关系不删文章）
     *
     * @param bookId    书籍ID
     * @param chapterId 章节ID
     * @param userId    当前登录用户ID
     */
    @Override
    public void removeChapter(Long bookId, Long chapterId, Long userId) {

        /**
         * 移除章节流程：
         *
         * ① 书籍归属校验：只有书的作者能移除章节。
         * ② 章节归属校验：该章节必须真的挂在这本书下（防伪造跨书章节ID）。
         * ③ 移除：只删“书-文章”挂载关系，文章本身不删。
         * ④ 历史联动：不跨域回写——指向被移除章节的阅读历史行 chapter_id 悬空，
         *    由阅读历史列表容错（书名+最后阅读时间仍展示，章节信息留空），不物理删历史行。
         */

        // 步骤1：书籍归属校验
        requireOwnedBook(bookId, userId);
        // 步骤2：章节归属校验：chapter_id 不归属 book_id 拒绝伪造跨书章节
        BookChapter chapter = bookChapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getBookId().equals(bookId)) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_MISMATCH);
        }
        // 步骤3：仅移除挂载关系不删文章；被移除章节的阅读历史行 chapter_id 悬空由列表容错（不物理删历史行）
        int deleted = bookChapterMapper.deleteById(chapterId);
        // 步骤4（批次十一 11.2 + 11.3 口径修正）：确实删掉挂载行、且该章节当时属于「公开可见」才 -1。
        // 与挂章口径对齐：私密/仅关注者/未过审/已删文章从未计入章节数，移除时不能误减。
        // 展示型计数同步失败只告警，不回滚已成功的删除
        if (deleted > 0 && isPubliclyVisibleChapter(chapter.getArticleId())) {
            try {
                contentStatSupport.decreaseChapter(bookId);
            } catch (Exception e) {
                log.warn("[章节统计] 章节数 -1 失败，已跳过：bookId={}", bookId, e);
            }
        }
    }

    /**
     * 判断章节文章当前是否算「公开可见章节」（用于章节数增量的口径对齐）。
     *
     * <p>口径：文章未软删 + 已发布(status=2) + 公开(visibility=1)，与回填脚本一致；
     * 文章已删/不存在一律按不可见处理（既不计入，也不递减）。</p>
     *
     * @param articleId 章节挂载的文章ID
     * @return true=计入书籍卡片的可见章节数
     */
    private boolean isPubliclyVisibleChapter(Long articleId) {
        // 文章查询：章节挂载行存在但文章已被物理清理时返回 null，按不可见处理
        Article article = articleMapper.selectById(articleId);
        return article != null
                && article.getDelFlag() == DelFlag.NOT_DELETED
                && article.getStatus() == ArticleStatusEnum.PUBLISHED
                && article.getVisibility() == VisibilityEnum.PUBLIC;
    }

    /**
     * 章节批量重排（owner，批次5 2026-11-02）：前端拖拽后全量提交新序，两阶段偏移落库
     *
     * @param bookId 书籍ID
     * @param dto    重排清单（全量：[{chapterId, newChapterNo}]）
     * @param userId 当前登录用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reorderChapters(Long bookId, BookChapterReorderDTO dto, Long userId) {

        /**
         * 章节重排流程（两阶段偏移，解 uk(book_id, chapter_no) 唯一键冲突）：
         *
         * ① owner + 状态校验：仅书籍作者可重排；下架书拒重排（与挂章/编辑元数据同口径——
         *    下架=对外终止态，冻结组织结构调整）。
         * ② book 粒度分布式锁：防多实例/多端并发重排互踩（抢锁失败按请求过频拒绝，稍后重试）。
         * ③ 读整书章节行 + 整单校验：清单与实际章节一一对应、新序号恰为 1..N 连续无重复——
         *    校验不通过整单拒绝，杜绝重排后序号空洞/重复。
         * ④ 阶段一：整书全部章节序号 += 偏移量挪进临时区（避开唯一键冲突）；
         *    阶段二：按清单落正值为新序号（CASE WHEN 单语句批量覆盖）。
         *    禁止「先删后插」：章节行含文章挂载关系，删了重插会丢行。
         *    并发兜底说明：锁释放在事务提交前有极窄窗口，但阶段二落的是绝对值新序，
         *    后到事务基于最新提交值重排仍收敛为提交者的目标布局，DB 行锁保证写不交错。
         */

        // 步骤1：owner 校验（软删书按不存在处理）+ 下架书拒重排
        Book book = requireOwnedBook(bookId, userId);
        if (book.getStatus() == BookStatusEnum.TAKEN_DOWN) {
            throw new BizException(ErrorCode.BOOK_STATUS_INVALID);
        }
        // 步骤2：book 粒度分布式锁（等待 3s，抢不到说明有并发重排进行中）
        String lockKey = CacheConstant.LOCK_PREFIX + "content:book:chapter-reorder:" + bookId;
        if (!distributedLock.tryLock(lockKey, 3, TimeUnit.SECONDS)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
        try {
            // 步骤3：读整书章节行（重排为全量提交，不按清单局部更新）
            List<BookChapter> chapters = bookChapterMapper.selectList(new LambdaQueryWrapper<BookChapter>()
                    .eq(BookChapter::getBookId, bookId));
            List<BookChapterReorderDTO.ReorderItem> items = dto.getItems();
            // 整单校验：清单完整性 + 新序 1..N（不通过整单拒绝）
            validateReorderList(chapters, items);
            // 步骤4-阶段一：整书序号偏移进临时区（避开 uk_book_chapter_no 唯一键冲突）
            bookChapterMapper.shiftChapterNoToTempArea(bookId, ContentLimitConstant.BOOK_CHAPTER_REORDER_OFFSET);
            // 步骤4-阶段二：按清单落正值为新序号（1..N，一次覆盖全部章节行）
            bookChapterMapper.applyChapterNo(bookId, items);
        } finally {
            // 锁必须释放（leaseTime 兜底防死锁，正常路径 finally 显式释放）
            distributedLock.unlock(lockKey);
        }
    }

    /**
     * 重排清单整单校验（reorderChapters 专用）：五道检查任一不过整单拒绝
     *
     * @param chapters 本书当前全部章节行
     * @param items    前端提交的重排清单
     */
    private void validateReorderList(List<BookChapter> chapters,
                                     List<BookChapterReorderDTO.ReorderItem> items) {
        // 检查①：章节数上限兜底（防超大清单批量 update 失控）
        if (chapters.size() > ContentLimitConstant.BOOK_CHAPTER_MAX) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_REORDER_INVALID);
        }
        // 检查②：清单条数必须与实际章节数一致（全量提交语义）
        if (items == null || items.size() != chapters.size()) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_REORDER_INVALID);
        }
        // 检查③：章节ID 一一对应——清单内无重复章节，且与本书章节集合完全相等（缺/多/跨书均拒绝）
        Set<Long> actualIds = chapters.stream()
                .map(BookChapter::getId).collect(Collectors.toSet());
        Set<Long> itemIds = items.stream()
                .map(BookChapterReorderDTO.ReorderItem::getChapterId).collect(Collectors.toSet());
        if (itemIds.size() != items.size() || !itemIds.equals(actualIds)) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_REORDER_INVALID);
        }
        // 检查④：新序号无重复且恰好构成 1..N（连续无空洞）
        Set<Integer> newNos = items.stream()
                .map(BookChapterReorderDTO.ReorderItem::getNewChapterNo)
                .collect(Collectors.toCollection(HashSet::new));
        if (newNos.size() != items.size()) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_REORDER_INVALID);
        }
        for (int i = 1; i <= items.size(); i++) {
            if (!newNos.contains(i)) {
                throw new BizException(ErrorCode.BOOK_CHAPTER_REORDER_INVALID);
            }
        }
        // 检查⑤：防御性预检——存量序号必须小于偏移量（保证临时区不与正序区重叠；
        // 正常数据 chapter_no ≤ 章节数上限 1000，本检查理论上不触发，防历史脏数据）
        boolean anyOverflow = chapters.stream()
                .anyMatch(c -> c.getChapterNo() >= ContentLimitConstant.BOOK_CHAPTER_REORDER_OFFSET);
        if (anyOverflow) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_REORDER_INVALID);
        }
    }

    /**
     * 归属校验复用：书籍存在（未软删）且属本人，否则按码拒绝
     *
     * @param id     书籍ID
     * @param userId 当前登录用户ID
     * @return 已校验书籍实体
     */
    private Book requireOwnedBook(Long id, Long userId) {
        // 步骤1：查书籍（软删书按不存在处理，不暴露存在性）
        Book book = bookMapper.selectById(id);
        if (book == null || book.getDelFlag() == DelFlag.DELETED) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        // 步骤2：归属校验（非本人拒绝）
        if (!book.getAuthorId().equals(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return book;
    }

    // ==================== 阶段17：章节阅读编排（详情 / 列表 / 章节详情） ====================

    /**
     * 书籍详情（可选登录，阶段17）。
     * <p>流程：可读校验 → 可见性范围计算 → 章节数/首尾章 → 上次阅读（登录且章节当前可见才回填）→
     * 收藏状态 → 组装；无可见章节时 count=0、first/latest/lastRead 均 null</p>
     */
    @Override
    public BookDetailVO getDetail(Long bookId, Long viewerId) {
        // 步骤1：书籍可读校验（不存在/已删/下架且非作者 → BOOK_NOT_FOUND）
        Book book = requireReadableBook(bookId, viewerId);
        // 步骤2：按查看者计算章节可见性范围（作者全可见/匿名仅公开/登录非作者按关注回查）
        List<Integer> visibilities = resolveChapterVisibilities(book, viewerId);
        // 步骤3：可见章节数与首/最新章定位（无可见章节时跳过首尾查询）
        Long count = bookChapterMapper.countVisibleChapters(bookId, visibilities);
        boolean hasChapters = count != null && count > 0;
        BookChapterVO first = hasChapters ? bookChapterMapper.selectFirstVisible(bookId, visibilities) : null;
        BookChapterVO latest = hasChapters ? bookChapterMapper.selectLatestVisible(bookId, visibilities) : null;

        // 步骤4：上次阅读回填——匿名跳过；登录取历史后按当前可见性复核（章节已删/超出可见范围 → 置 null 不阻断）
        ReadingHistoryVO lastRead = null;
        if (viewerId != null) {
            lastRead = readingHistoryService.getLastRead(viewerId, bookId);
            if (lastRead != null) {
                BookChapterVO reread = bookChapterMapper.selectVisibleChapter(bookId, lastRead.getChapterId(), visibilities);
                if (reread == null) {
                    lastRead = null;
                }
            }
        }
        // 步骤5：书籍收藏状态（匿名 false）
        boolean favorited = viewerId != null && favoriteStateSupport.isFavorited(
                viewerId, FavoriteTargetTypeEnum.BOOK.getCode(), bookId);
        // 步骤6：组装出参
        return new BookDetailVO()
                .setId(book.getId())
                .setAuthorId(book.getAuthorId())
                .setTitle(book.getTitle())
                .setIntro(book.getIntro())
                .setCoverFileId(book.getCoverFileId())
                .setCategory(book.getCategory())
                .setStatus(book.getStatus())
                .setChapterCount(count)
                .setFirstChapterId(first != null ? first.getChapterId() : null)
                .setFirstChapterNo(first != null ? first.getChapterNo() : null)
                .setLatestChapterId(latest != null ? latest.getChapterId() : null)
                .setLatestChapterNo(latest != null ? latest.getChapterNo() : null)
                .setLastReadChapterId(lastRead != null ? lastRead.getChapterId() : null)
                .setLastReadChapterNo(lastRead != null ? lastRead.getChapterNo() : null)
                .setLastReadChapterTitle(lastRead != null ? lastRead.getChapterTitle() : null)
                .setLastReadTime(lastRead != null ? lastRead.getLastReadTime() : null)
                .setCreateTime(book.getCreateTime())
                .setUpdateTime(book.getUpdateTime())
                .setFavorited(favorited);
    }

    /**
     * 章节列表分页（可选登录，阶段17）。
     * <p>可读校验 → 可见性范围 → SQL 层分页（chapter_no 升序）；空页正常返回</p>
     */
    @Override
    public PageResult<BookChapterVO> pageChapters(Long bookId, Long viewerId, long pageNo, long pageSize) {
        // 步骤1：书籍可读校验
        Book book = requireReadableBook(bookId, viewerId);
        // 步骤2：按查看者计算章节可见性范围
        List<Integer> visibilities = resolveChapterVisibilities(book, viewerId);
        // 步骤3：分页参数钳制（Service 直收 long 入参，不经过 PageQuery 兜底）后走 SQL 分页
        Page<BookChapterVO> page = bookChapterMapper.selectVisibleChapterPage(
                Page.of(Math.max(pageNo, PageConstant.DEFAULT_PAGE_NO),
                        Math.min(Math.max(pageSize, 1), PageConstant.MAX_PAGE_SIZE)),
                bookId, visibilities);
        // 步骤4：MP Page → PageResult（BookChapterVO 已由 SQL 投影直出，无二次转换）
        return PageResult.of(page, vo -> vo);
    }

    /**
     * 章节详情（可选登录，阶段17）。
     * <p>流程：书可读 → 章节归属校验 → 正文复用文章可见性判定链（不可见抛 ARTICLE_NOT_FOUND）→
     * 可见性范围 → 总数与相邻章 → 组装（chapterTitle 取文章标题）；
     * 浏览计数与阅读历史等副作用不在此处（HTTP 信息与 fail-safe 收口在 Controller/Support，见 D/E 节）</p>
     */
    @Override
    public BookChapterDetailVO getChapterDetail(Long bookId, Long chapterId, Long viewerId) {
        // 步骤1：书籍可读校验
        Book book = requireReadableBook(bookId, viewerId);
        // 步骤2：章节存在且归属本书校验（防伪造跨书章节ID）
        BookChapter chapter = bookChapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getBookId().equals(bookId)) {
            throw new BizException(ErrorCode.BOOK_CHAPTER_MISMATCH);
        }
        // 步骤3：章节正文复用文章详情链（status/visibility/配图渲染/计数回填；不可见抛 ARTICLE_NOT_FOUND）
        ArticleDetailVO article = articleService.getDetail(chapter.getArticleId(), viewerId);
        // 步骤4：按查看者计算可见性范围后取总数与相邻章节
        List<Integer> visibilities = resolveChapterVisibilities(book, viewerId);
        Long count = bookChapterMapper.countVisibleChapters(bookId, visibilities);
        BookChapterVO prev = bookChapterMapper.selectPrevVisible(bookId, chapter.getChapterNo(), visibilities);
        BookChapterVO next = bookChapterMapper.selectNextVisible(bookId, chapter.getChapterNo(), visibilities);
        // 步骤5：组装出参（相邻章 null=已是首/末章或相邻章不可见，前端隐藏按钮）
        return new BookChapterDetailVO()
                .setBookId(bookId)
                .setBookTitle(book.getTitle())
                .setBookAuthorId(book.getAuthorId())
                .setChapterId(chapterId)
                .setChapterNo(chapter.getChapterNo())
                .setChapterTitle(article.getTitle())
                .setChapterCount(count)
                .setArticle(article)
                .setPrevChapterId(prev != null ? prev.getChapterId() : null)
                .setPrevChapterNo(prev != null ? prev.getChapterNo() : null)
                .setNextChapterId(next != null ? next.getChapterId() : null)
                .setNextChapterNo(next != null ? next.getChapterNo() : null);
    }

    // ==================== P2 书籍发现入口（2026-10-28）：公开列表 / 我的书籍 ====================

    /**
     * 公开书籍列表（匿名）：硬条件 status != 下架（连载中/已完结对外可见）&& 未软删；
     * category 可选精确过滤；排序走 PageQuery（默认 update_time 倒序，匿名仅通用时间列白名单）。
     * <p>一期不做 keyword 搜索（search 模块书籍索引为外部依赖，无索引 SQL LIKE 全表扫不立项）</p>
     *
     * @param query 查询条件（分页 + category 过滤）
     * @return 书籍卡片分页
     */
    @Override
    public PageResult<BookCardVO> listPublic(BookQueryDTO query) {

        /**
         * 公开书籍列表流程：
         *
         * ① 组装查询条件：status != 下架 && del_flag=未删除（硬条件）；category 传了才拼。
         * ② MyBatis-Plus 分页查询：分页兜底由 PageQuery 收口，排序默认 update_time 倒序。
         * ③ 封装 PageResult 返回（total/totalPages/hasNext 供前端分页组件）。
         */

        // 步骤1：查询条件：下架书不对外（requireReadableBook 同口径）；软删书不过滤条件兜死；
        // category 精确匹配（自由字符串，MP 参数化无注入风险）
        LambdaQueryWrapper<Book> wrapper = new LambdaQueryWrapper<Book>()
                .ne(Book::getStatus, BookStatusEnum.TAKEN_DOWN)
                .eq(Book::getDelFlag, DelFlag.NOT_DELETED)
                .eq(query.getCategory() != null && !query.getCategory().isBlank(),
                        Book::getCategory, query.getCategory());
        // 步骤2：分页查询（PageQuery 内置分页兜底 + 排序三级优先级收口）
        Page<Book> page = bookMapper.selectPage(query.toMpPage(), wrapper);
        // 步骤3：分页信息 + 记录转卡片 VO 一行收敛
        return PageResult.of(page, this::toCardVO);
    }

    /**
     * 我的书籍分页（登录）：author_id=当前登录人硬收口（不信任前端）&& 未软删（软删书不展示）；
     * 含下架书（作者侧自查）；category/status/keyword 可选过滤；
     * 排序走 PageQuery sortBy 时间列白名单（登录端点开放，白名单外回退默认 update_time 倒序）。
     * 复用文章 my 列表模式（ArticleServiceImpl#listMy 同款编排）
     *
     * @param query  查询条件（分页 + category/status/keyword）
     * @param userId 当前登录用户ID（归属硬条件）
     * @return 书籍卡片分页
     */
    @Override
    public PageResult<BookCardVO> listMy(BookMyQueryDTO query, Long userId) {

        /**
         * 我的书籍列表流程：
         *
         * ① 组装查询条件：author_id=本人（硬条件）+ 未软删；category/status 传了才拼；
         *    keyword 书名模糊匹配（作者侧找书用，idx_author_id 前缀过滤后量小）。
         * ② MyBatis-Plus 分页查询：分页兜底由 PageQuery 收口；排序走 sortBy 白名单。
         * ③ 封装 PageResult 返回。
         */

        // 步骤1：查询条件：本人书籍（硬条件，不占参数位不可伪造）+ 未软删；可选过滤逐项条件拼接
        LambdaQueryWrapper<Book> wrapper = new LambdaQueryWrapper<Book>()
                .eq(Book::getAuthorId, userId)
                .eq(Book::getDelFlag, DelFlag.NOT_DELETED)
                .eq(query.getStatus() != null, Book::getStatus, query.getStatus())
                .eq(query.getCategory() != null && !query.getCategory().isBlank(),
                        Book::getCategory, query.getCategory())
                .like(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        Book::getTitle, query.getKeyword());
        // 步骤2：分页查询（PageQuery 内置分页兜底 + 排序三级优先级收口）
        Page<Book> page = bookMapper.selectPage(query.toMpPage(), wrapper);
        // 步骤3：分页信息 + 记录转卡片 VO 一行收敛
        return PageResult.of(page, this::toCardVO);
    }

    /**
     * 书籍实体转列表卡片 VO（公开列表/我的书籍共用，字段直拷无加工）
     *
     * @param book 书籍实体
     * @return 书籍卡片 VO
     */
    private BookCardVO toCardVO(Book book) {
        return new BookCardVO()
                .setId(book.getId())
                .setAuthorId(book.getAuthorId())
                .setTitle(book.getTitle())
                .setIntro(book.getIntro())
                .setCoverFileId(book.getCoverFileId())
                .setCategory(book.getCategory())
                .setStatus(book.getStatus())
                .setCreateTime(book.getCreateTime())
                .setUpdateTime(book.getUpdateTime());
    }

    /**
     * 书籍可读校验（阅读编排入口复用，阶段17）。
     * <p>口径：未软删 + 下架书仅作者可读（作者自查自己的下架书，不对外暴露）</p>
     *
     * @param bookId   书籍ID
     * @param viewerId 查看者ID（null=匿名）
     * @return 已校验书籍实体
     */
    private Book requireReadableBook(Long bookId, Long viewerId) {
        Book book = bookMapper.selectById(bookId);
        // 不存在/已删：按书籍不存在处理
        if (book == null || book.getDelFlag() == DelFlag.DELETED) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        // 下架书仅作者可读（匿名 viewerId 与作者ID比对必为 false，天然拒绝）
        if (book.getStatus() == BookStatusEnum.TAKEN_DOWN && !book.getAuthorId().equals(viewerId)) {
            throw new BizException(ErrorCode.BOOK_NOT_FOUND);
        }
        return book;
    }

    /**
     * 章节可见性范围计算（阶段17，B 节 Mapper visibilities 参数的取值来源）。
     * <p>作者 → [1,2,3] 全可见（自查草稿/私密章节）；
     * 匿名 → [1] 仅公开；
     * 登录非作者 → 书内无关注者章节直接 [1]（省一次 social 回查）；
     * 有 → 关注关系判定 [1,3] 或 [1]，social 不可用 catch 降级 [1] 并 warn（列表不因 social 抖动整体失败）</p>
     *
     * @param book     已校验书籍实体
     * @param viewerId 查看者ID（null=匿名）
     * @return 可见性 code 集合（VisibilityEnum）
     */
    private List<Integer> resolveChapterVisibilities(Book book, Long viewerId) {
        // 作者看自己的全部章节（含草稿/私密/关注者可见）
        if (viewerId != null && viewerId.equals(book.getAuthorId())) {
            return List.of(VisibilityEnum.PUBLIC.getCode(), VisibilityEnum.PRIVATE.getCode(),
                    VisibilityEnum.FOLLOWERS.getCode());
        }
        // 匿名：仅公开
        if (viewerId == null) {
            return List.of(VisibilityEnum.PUBLIC.getCode());
        }
        // 登录非作者：书内无关注者章节就无需回查关注关系
        if (!Boolean.TRUE.equals(bookChapterMapper.existsFollowersChapter(book.getId()))) {
            return List.of(VisibilityEnum.PUBLIC.getCode());
        }
        try {
            // 关注者可见公开+关注者章节；未关注仅公开（缓存 miss 按 false 处理，宁严勿松）
            return followRelationCache.isFollowing(book.getAuthorId(), viewerId)
                    ? List.of(VisibilityEnum.PUBLIC.getCode(), VisibilityEnum.FOLLOWERS.getCode())
                    : List.of(VisibilityEnum.PUBLIC.getCode());
        } catch (BizException e) {
            // social 不可用：降级为仅公开（章节阅读主链路不因 social 抖动整体失败）
            log.warn("关注关系回查失败，书籍章节可见性降级为仅公开，bookId={}, viewerId={}",
                    book.getId(), viewerId, e);
            return List.of(VisibilityEnum.PUBLIC.getCode());
        }
    }
}