package com.litisle.litislecontent.service;

import com.litisle.litisleapi.event.moderation.ModerationAppealResultEvent;
import com.litisle.litisleapi.event.moderation.ModerationResultEvent;
import com.litisle.litisleapi.event.user.UserBannedEvent;

/**
 * 文章审核结果回写服务（板块三 3-5，§4.5.2）——消费端业务逻辑载体。
 *
 * 【职责】消费 review 侧下发的审核结果/申诉改判事件，完成：
 * - 双链路聚合判定（文本 scene=ARTICLE + 配图复审 scene=ARTICLE_IMG_RE_REVIEW，
 *   放行=文本 PASS 且（无图或图片 PASS）；任一 REJECT→整文驳回）
 * - article 状态 CAS 回写（1→2 发布 / 1→3 首发驳回 / 2→4 复审下架；配套事件与缓存失效）
 * - last-hash 免审键写入（4-3.1：文本链路主键 + 图片链路连带键）/ 申诉改判删除（5-4.5）
 *
 * 【防毒丸契约】本服务抛出的 BizException 由 MqAckHelper 视为业务性失败 ack 丢弃；
 * 非预期异常冒泡触发指数退避延迟重投（CAS 与 Redis 槽操作全部幂等，重试安全）
 *
 * @author litisle
 */
public interface ArticleReviewWriteBackService {

    /**
     * 处理审核结果事件（moderation.text.result 队列消费）
     *
     * @param event 审核结果事件（litisle-api 契约；文章双链路 scene 混合同队列到达）
     */
    void handleResult(ModerationResultEvent event);

    /**
     * 处理申诉改判结果事件（moderation.appeal.result 队列消费）
     *
     * @param event 申诉改判事件（appealPass=true 且文章侧 CAS 3→2 恢复发布）
     */
    void handleAppeal(ModerationAppealResultEvent event);

    /**
     * 处理用户封禁事件（user.banned 队列消费，板块八 [8.2]）：
     * 该作者全部已发布文章批量置 4-下架 + 逐篇发 content.unpublished + 本地缓存三键失效
     *
     * @param event 用户封禁事件（litisle-api 契约；user 侧封禁事务提交后发布）
     */
    void handleUserBanned(UserBannedEvent event);
}