package com.litisle.litislecontent.service.support;

import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litisleapi.event.content.ContentArticleUpdatedEvent;
import com.litisle.litisleapi.event.content.ContentPublishedEvent;
import com.litisle.litisleapi.event.content.ContentUnpublishedEvent;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.mq.ContentEventProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 文章事务后事件域支撑组件（原 ArticleServiceImpl 事件域私有方法收口）。
 *
 * 作用：
 * 统一收口 发布/建档/编辑/重提/软删 五条链路共用的「事务提交后发 MQ 事件」注册逻辑，
 * 所有方法都基于 TransactionSynchronizationManager 注册 afterCommit 回调——
 * 【事务顺序铁律】本地事务提交 → 事件发送（事务内不发消息，防回滚后消息已发出导致脏数据）；
 * 发送失败由 MQ 重投/DLQ 兜底，消费端按 eventId/bizType+bizId+scene 幂等。
 *
 * 覆盖事件：
 *   content.submitted（送审）/ image.submit（配图复审）/ content.published（发布收录）
 *   / content.unpublished（下架移除）/ content.article-updated（元数据索引局部刷新）
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class ArticleEventSupport {

    /** 内容事件生产者（submitted/published/unpublished/image.submit 事件发送） */
    private final ContentEventProducer contentEventProducer;

    /**
     * 注册事务提交后发送 content.submitted（事务顺序铁律：本地事务提交 → 事件发送，失败重投/DLQ）；
     * 事件体不含正文，review 消费端按 bizType+bizId+scene 定位任务后经 ContentClient 回查
     *
     * @param articleId   文章ID（事件 bizId）
     * @param contentHash 送审文本指纹（SubmitTextAssembler 同一规则：标题+分隔符+正文 SHA-256）
     */
    public void registerSubmitEventAfterCommit(Long articleId, String contentHash) {
        // 组装送审事件体（自动机审 + 文章业务类型 + 场景码 + 指纹，review 端回查内容）
        TextSubmitEvent event = new TextSubmitEvent();
        event.setModType(ModerationConstant.MOD_TYPE_AUTO);
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(articleId);
        event.setScene(DfaScenes.ARTICLE);
        event.setContentHash(contentHash);
        event.setRetryCount(0);
        //在当前事务成功提交之后，再执行发送事件的逻辑。事务由事务管理器进行管理
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contentEventProducer.sendSubmitted(event);
            }
        });
    }

    /**
     * 注册事务提交后发送 image.submit（配图发布复审，事务顺序铁律：本地事务提交 → 事件发送，失败重投/DLQ）；
     * bizType=1-文章、bizId=articleId、scene=ARTICLE_IMG_RE_REVIEW、contentHash=配图聚合指纹，
     * review 消费端按 bizType+bizId+scene 定位文章级复审任务后反查配图逐张送审并聚合流转
     *
     * @param articleId         文章ID（事件 bizId）
     * @param aggregatedImgHash 配图聚合指纹（事件 contentHash）
     */
    public void registerImageSubmitEventAfterCommit(Long articleId, String aggregatedImgHash) {
        // 组装配图复审事件体（bizId 为字符串口径，与 review 端图片复审任务定位规则对齐）
        ImageSubmitEvent event = new ImageSubmitEvent();
        event.setModType(ModerationConstant.MOD_TYPE_AUTO);
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(String.valueOf(articleId));
        event.setScene(ModerationConstant.SCENE_ARTICLE_IMG_RE_REVIEW);
        event.setContentHash(aggregatedImgHash);
        event.setRetryCount(0);
        // 事务提交后再发送（回滚不产生脏事件）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contentEventProducer.sendImageSubmit(event);
            }
        });
    }

    /**
     * 事务提交后发 content.unpublished（事务顺序铁律：本地事务提交 → 事件发送，失败重投/DLQ）
     *
     * @param article 触发下架的文章（取 id/contentHash 填事件体）
     * @param reason  下架原因（ContentBizConstant.UNPUBLISH_REASON_* 字典）
     */
    public void publishUnpublishedAfterCommit(Article article, int reason) {
        // 组装下架事件体（search/ai 消费后移除索引与向量，防隐私残留）
        ContentUnpublishedEvent event = new ContentUnpublishedEvent();
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(article.getId());
        event.setContentHash(article.getContentHash());
        event.setReason(reason);
        // 事务提交后再发送（回滚不产生脏事件）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contentEventProducer.sendUnpublished(event);
            }
        });
    }

    /**
     * 事务提交后发送 content.published（转公开不重审场景，[4.1]）：
     * 事件体只带指纹（bizType/bizId/contentHash），消费方按需回查数据库确认当前真实状态
     *
     * @param article 已发布文章（contentHash 取库中现值，方案B 禁改正文故未变）
     */
    public void publishPublishedAfterCommit(Article article) {
        // 组装发布事件体（search 索引/ai 向量/notification 收录都靠它）
        ContentPublishedEvent event = new ContentPublishedEvent();
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(article.getId());
        event.setContentHash(article.getContentHash());
        // 事务提交后再发送（回滚不产生脏事件）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contentEventProducer.sendPublished(event);
            }
        });
    }

    /**
     * 事务提交后发送 content.article-updated（已发布文章元数据编辑，批次A A2 定案 / 批次B B7 发布点接入）。
     * <p>事件体只带定位键（bizType/bizId/contentHash），search 收到后回查权威元数据并局部更新索引字段
     * （不动向量与 chunks）；不发事件则已发布文章改元数据后索引字段将永久过期。</p>
     *
     * @param article 已发布文章（contentHash 取库中现值，元数据编辑不改正文故不变）
     */
    public void publishArticleUpdatedAfterCommit(Article article) {
        // 组装元数据更新事件体（只带定位键，search 回查权威元数据局部刷新）
        ContentArticleUpdatedEvent event = new ContentArticleUpdatedEvent();
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(article.getId());
        event.setContentHash(article.getContentHash());
        // 事务提交后才发送：回滚不产生脏事件（消费端按 eventId 幂等）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contentEventProducer.sendArticleUpdated(event);
            }
        });
    }
}
