package com.litisle.litislecontent.service.support;

import com.litisle.litislecontent.service.ReadingHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 章节阅读副作用组件（阶段17：浏览计数 + 阅读历史落库收口，均 fail-safe 不影响章节响应）
 *
 * <p>口径：</p>
 * <ul>
 *   <li>浏览计数：匿名与普通读者都记，作者本人浏览不计（11.11-A 自浏览排除），复用 ViewCounterSupport
 *       （PV + 去重窗口，Redis 异步刷回），与文章详情计数口径一致</li>
 *   <li>阅读历史：仅「登录 + 非书籍作者」时记（阶段15 自读口径），
 *       走 ReadingHistoryService#recordBookRead 显式 book 上下文（uk_user_biz 幂等）</li>
 * </ul>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookReadSupport {

    /** 浏览计数组件（PV + 去重窗口 + Redis 异步刷回，与文章详情共用） */
    private final ViewCounterSupport viewCounterSupport;

    /** 阅读历史服务（recordBookRead 显式书籍上下文落历史） */
    private final ReadingHistoryService readingHistoryService;

    /**
     * 章节阅读副作用记录（浏览计数 + 阅读历史，两步各自 fail-safe）
     *
     * <p>调用时机：章节详情可见性判定通过、响应组装前/后均可（本组件不返回值、不抛业务异常）；
     * 浏览计数按章节正文所属文章记账（article 维度 PV），阅读历史按书籍维度落（biz_type=1）</p>
     *
     * @param bookId       书籍ID
     * @param chapterId    章节ID
     * @param articleId    章节正文所属文章ID（浏览计数记账维度）
     * @param bookAuthorId 书籍作者ID（自读过滤基准）
     * @param viewerId     查看者ID（null=匿名）
     * @param ip           客户端 IP（匿名浏览去重基准之一）
     * @param ua           User-Agent（匿名浏览去重基准之一）
     */
    public void recordChapterRead(Long bookId, Long chapterId, Long articleId,
                                  Long bookAuthorId, Long viewerId, String ip, String ua) {
        // 步骤1：浏览计数——匿名与普通读者都记，作者本人浏览不计（11.11-A）；失败只记日志不影响章节响应
        try {
            viewCounterSupport.recordView(articleId, bookAuthorId, viewerId, ip, ua);
        } catch (Exception e) {
            log.warn("章节浏览计数失败（fail-safe 忽略），bookId={}, chapterId={}, articleId={}",
                    bookId, chapterId, articleId, e);
        }
        // 步骤2：阅读历史——仅登录非作者（自读不落，阶段15 口径）；失败同样只记日志
        if (viewerId != null && !viewerId.equals(bookAuthorId)) {
            try {
                readingHistoryService.recordBookRead(viewerId, bookId, chapterId);
            } catch (Exception e) {
                log.warn("章节阅读历史落库失败（fail-safe 忽略），viewerId={}, bookId={}, chapterId={}",
                        viewerId, bookId, chapterId, e);
            }
        }
    }
}
