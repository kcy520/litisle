package com.litisle.litislecontent.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.litislecontent.domain.vo.ReadingHistoryItemVO;
import com.litisle.litislecontent.domain.vo.ReadingHistoryVO;

/**
 * 阅读历史域服务接口——REST 由 ReadingHistoryController 调用，
 * 详情自动落历史由 ReadingHistorySupport 调用：
 *
 * 【核心机制（§6.6）】
 * - 单表双类型：biz_type=1 书籍历史（含 chapter_id）、biz_type=2 独立文章历史（chapter_id 为 null）；
 *   唯一键 uk_user_biz(user_id, biz_type, biz_id) 保证每人每类型每对象一行，update_time 即最后阅读时间
 * - 参数合法性：report/recordBookRead 校验 chapter_id 归属 book_id（book_chapter 反查，拒绝伪造跨书章节）
 * - 一致性：UPSERT 冲突转 update（uk_user_biz）；report 路径仅前进不回退，
 *   自动落历史路径按「最后一次阅读」语义刷新（回读旧章可回退）
 */
public interface ReadingHistoryService {

    /**
     * 历史上报（书籍阅读页显式入口；服务端 @RateLimiter 兜底 + 前端 30s 节流；仅前进不回退）
     * <p>落库固定 biz_type=1、bizId=bookId</p>
     *
     * @param userId    读者ID
     * @param bookId    书籍ID
     * @param chapterId 最后阅读章节ID
     */
    void report(Long userId, Long bookId, Long chapterId);

    /**
     * 续读查询（取该用户在该书的最后阅读章节）
     *
     * @param userId 读者ID
     * @param bookId 书籍ID
     * @return 章节ID（无历史返回 null）
     */
    Long getLastChapter(Long userId, Long bookId);

    /**
     * 上次阅读详情（书籍维度，供阶段17 书籍详情展示「上次读到第N章」）
     *
     * @param userId 读者ID
     * @param bookId 书籍ID
     * @return 上次阅读信息（无历史、章节行已删或章节文章已删返回 null；
     *         可见性由阶段17 调用方按书籍可见性 scope 复核）
     */
    ReadingHistoryVO getLastRead(Long userId, Long bookId);

    /**
     * 分页查询当前用户阅读历史（update_time 倒序，id 作稳定排序 tie-breaker）
     * <p>对象已软删的历史行在 SQL 层过滤，total 只统计可见行</p>
     *
     * @param userId   当前登录用户ID
     * @param bizType  阅读对象类型：null-全部（混合时间线）1-书籍 2-独立文章；非法值抛 PARAM_ERROR
     * @param pageNo   页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 历史项分页结果（对象/章节信息已批量装配；空历史返回空页）
     */
    Page<ReadingHistoryItemVO> pageMyHistory(Long userId, Integer bizType, long pageNo, long pageSize);

    /**
     * 文章详情被阅读后落历史（自动路径，内部完成「归属判定 + UPSERT」；fail-safe 由调用方承接）：
     * 0 条挂载=独立文章（biz_type=2）；1 条=章节文章（biz_type=1）；多书挂载=歧义跳过（debug 留痕），
     * 交由书籍阅读页 PUT 显式上报。不做「仅前进」校验（最后一次阅读语义，回读旧章可回退）。
     *
     * @param userId    读者ID（调用方已过滤匿名与自读）
     * @param articleId 被阅读的文章ID
     */
    void recordFromArticle(Long userId, Long articleId);

    /**
     * 章节详情被阅读后落书籍历史（显式 book 上下文，避免多书归属歧义；供阶段17 章节详情端点调用）。
     * 不做「仅前进」校验（最后一次阅读语义，update_time 刷新）。
     *
     * @param userId    读者ID
     * @param bookId    书籍ID
     * @param chapterId 章节ID（须归属 bookId，不匹配抛 BOOK_CHAPTER_MISMATCH）
     */
    void recordBookRead(Long userId, Long bookId, Long chapterId);
}
