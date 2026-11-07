package com.litisle.litislecontent.service.support;

import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.event.social.SocialLikedEvent;
import com.litisle.litisleapi.event.social.SocialUnlikedEvent;
import com.litisle.litislecontent.mq.ContentEventProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 点赞事务后事件支撑组件（批次十一 11.14：content 临时承载点赞功能后的跨模块生产者收口）。
 *
 * <p>作用：把「点赞/取消点赞成功」转换为 search 索引同步事件，并统一注册到
 * 本地事务提交后执行——【事务顺序铁律】先提交本地事务再发消息，事务回滚则不产生脏事件。</p>
 *
 * <p>事件契约：bizType=1-文章、bizId=文章ID、bizUserId=操作人、delta=+1（点赞）/-1（取消点赞），
 * 消费端按 eventId 幂等去重，重投不重复加减。</p>
 *
 * <p>书籍点赞不发事件：search 的 book_idx 没有 likeCnt 字段，书籍点赞只落 content 侧
 * book_stat.like_count（详见 11.14 口径），因此本组件只处理文章目标。</p>
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class LikeEventSupport {

    /** 内容事件生产者（social.liked / social.unliked 发送） */
    private final ContentEventProducer contentEventProducer;

    /**
     * 注册事务提交后发送点赞/取消点赞事件（仅文章目标调用）。
     *
     * @param userId    操作用户ID（事件 bizUserId）
     * @param articleId 文章ID（事件 bizId）
     * @param delta     点赞计数增量（1=点赞，-1=取消点赞；0 时不发事件）
     */
    public void publishLikeEventAfterCommit(Long userId, Long articleId, int delta) {
        // 没有实际计数变化时不发事件（防消费端做无意义的索引刷新）
        if (delta == 0) {
            return;
        }
        // 事务提交后再发送（回滚不产生脏事件；消费端按 eventId 幂等）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (delta > 0) {
                    // 点赞事件：search 累加 article_idx.like_cnt
                    SocialLikedEvent liked = new SocialLikedEvent();
                    liked.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
                    liked.setBizId(articleId);
                    liked.setBizUserId(userId);
                    liked.setDelta(delta);
                    contentEventProducer.sendSocialLiked(liked);
                } else {
                    // 取消点赞事件：search 递减 article_idx.like_cnt（带下限保护）
                    SocialUnlikedEvent unliked = new SocialUnlikedEvent();
                    unliked.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
                    unliked.setBizId(articleId);
                    unliked.setBizUserId(userId);
                    unliked.setDelta(delta);
                    contentEventProducer.sendSocialUnliked(unliked);
                }
            }
        });
    }
}
