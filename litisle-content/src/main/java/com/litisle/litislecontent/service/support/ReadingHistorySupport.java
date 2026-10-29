package com.litisle.litislecontent.service.support;

import com.litisle.litislecontent.service.ReadingHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 阅读历史自动落库支撑组件（与 ViewCounterSupport 并列，阶段15）。
 *
 * 作用：
 * 文章详情命中后的「阅读即落历史」单点收口——匿名/自读前置过滤 + 委托
 * ReadingHistoryService#recordFromArticle 完成归属判定与 UPSERT + fail-safe 异常吞掉，
 * 落历史失败不阻断详情返回；避免 Controller 直接操作 Service 与归属判定细节。
 *
 * 前置条件：仅在详情可见性判定通过后调用（不可见/已软删/详情为 null 不会走到这里）。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingHistorySupport {

    /** 阅读历史域服务（归属判定 + UPSERT 承接方） */
    private final ReadingHistoryService readingHistoryService;

    /**
     * 文章详情命中后自动落阅读历史（fail-safe：异常仅 warn，不阻断详情返回）。
     *
     * 过滤口径：
     * ① 匿名（viewerId=null）跳过，不落历史；
     * ② 自读不计（作者本人读自己的文章跳过），避免自己的文章占满书架。
     *
     * @param viewerId  查看者用户ID（null=匿名）
     * @param articleId 文章ID
     * @param authorId  文章作者ID（自读判定）
     */
    public void recordArticleHistory(Long viewerId, Long articleId, Long authorId) {
        // 过滤①：匿名无身份，不落历史
        if (viewerId == null) {
            return;
        }
        // 过滤②：自读不计，避免自己的文章占满书架
        if (viewerId.equals(authorId)) {
            return;
        }
        try {
            // 归属判定（独立文章/章节文章/多书歧义）+ UPSERT 由 Service 承接
            readingHistoryService.recordFromArticle(viewerId, articleId);
        } catch (Exception e) {
            // 落历史非详情核心链路：fail-safe 吞异常（丢一次历史 vs 详情打不开，取后者）
            log.warn("阅读历史自动落库失败（fail-safe 不阻断详情）：articleId={}, viewerId={}",
                    articleId, viewerId, e);
        }
    }
}
