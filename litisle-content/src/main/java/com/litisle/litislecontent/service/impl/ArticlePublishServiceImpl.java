package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonredis.login.LoginRetryLimiter;
import com.litisle.commonsecurity.crypto.PasswordCrypto;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.event.content.ContentPublishedEvent;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaHitResult;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litisleapi.dto.content.ContentPreviewCheckDTO;
import com.litisle.litisleapi.dto.content.ContentPreviewCheckVO;
import com.litisle.litisleapi.event.content.ContentUnpublishedEvent;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
import com.litisle.litisleapi.moderation.SubmitTextAssembler;
import com.litisle.litislecontent.cache.UnpublishedCacheCleaner;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.statemachine.ArticleStateMachine;
import com.litisle.litislecontent.domain.vo.ArticleBatchResultVO;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mq.ContentEventProducer;
import com.litisle.litislecontent.service.ArticlePublishService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布编排域实现（§6.1 同步段 A1→A3 / §6.3 批量 / 预检 / 密码校验）。
 *
 * 【发布编排（口径B）】同步段只做 A1 校验 + A2 免审直落 + A3 CAS 0→1 落库发事件，
 * 不同步等审；异步段（切片/聚合/回写）在 review 消费端收口，本段不做回写（防双写竞态）
 * 【事务顺序铁律】本地事务提交 → afterCommit 事件发送（失败重投/DLQ）
 * 【批量口径（§6.3）】单批 ≤ batch-max（防批量刷审）；逐篇独立事务（TransactionTemplate
 * 编程式事务，规避同类自调用 @Transactional 失效）；失败不回滚已成功篇目，逐篇回执可定位重提
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticlePublishServiceImpl implements ArticlePublishService {

    private final ArticleMapper articleMapper;

    private final ContentEventProducer contentEventProducer;

    private final ArticleStateMachine articleStateMachine;

    private final PasswordCrypto passwordCrypto;

    /** DFA 同步闸门门面（宿主本地 Bean，预检与发布闸门同源同快照） */
    private final DfaFacade dfaFacade;

    private final ContentProperties contentProperties;

    /** 编程式事务（逐篇独立事务边界；Boot 自动装配，同一 PlatformTransactionManager） */
    private final TransactionTemplate transactionTemplate;

    /** 下架审核缓存失效编排（[3.4] 方案 A：last-hash 精确删 + text/img-result SCAN 通配版本段） */
    private final UnpublishedCacheCleaner unpublishedCacheCleaner;

    /** 登录防爆破限频器（common-redis；[4.2] 密码访问失败计数/锁定/重置与 auth 同源同口径） */
    private final LoginRetryLimiter loginRetryLimiter;

    /** 字符串 Redis 模板（[4.2] 密码验证通过凭证标记写入） */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 批量发布（§6.3：单批 ≤ batch-max(20) 超限拒绝；逐篇独立事务 + 逐篇回执 {articleId, success, errorCode}；
     * 已发布篇目 CAS 不满足自动跳过幂等）
     *
     * @param articleIds 待发布文章ID列表
     * @param operatorId 操作人ID
     * @return 逐篇发布结果回执
     */
    @Override
    public List<ArticleBatchResultVO> publishBatch(List<Long> articleIds, Long operatorId) {
        // 步骤1：单批上限校验（§6.3 防批量刷审；超限整体拒绝 ARTICLE_BATCH_EXCEEDED）
        if (articleIds != null && articleIds.size() > contentProperties.getPublish().getBatchMax()) {
            throw new BizException(ErrorCode.ARTICLE_BATCH_EXCEEDED);
        }
        List<ArticleBatchResultVO> results = new ArrayList<>();
        if (articleIds == null || articleIds.isEmpty()) {
            return results;
        }
        // 步骤2：同批次共享批次ID（article.publish_batch_id，按批次展示/批量操作锚点）
        Long batchId = IdWorker.getId();
        // 步骤3：逐篇独立事务执行，失败不中断批次（逐篇回执可定位重提）
        for (Long articleId : articleIds) {
            try {
                transactionTemplate.executeWithoutResult(status -> doPublishSingle(articleId, operatorId, batchId));
                results.add(new ArticleBatchResultVO().setArticleId(articleId).setSuccess(true));
            } catch (BizException e) {
                // 失败篇目记录错误码回执（成功篇目不回滚，§6.3 事务边界）
                log.warn("批量发布单篇失败，articleId={}，operatorId={}，errorCode={}",
                        articleId, operatorId, e.getCode());
                results.add(new ArticleBatchResultVO().setArticleId(articleId)
                        .setSuccess(false).setErrorCode(String.valueOf(e.getCode())));
            }
        }
        return results;
    }

    /**
     * 批量改可见性（逐篇 CAS + 每篇独立发 content.unpublished/published 事件；转公开 CAS 限定 status=2 且不重审）
     *
     * @param articleIds 待改文章ID列表
     * @param target     目标可见性
     * @param operatorId 操作人ID
     */
    @Override
    public void batchVisibility(List<Long> articleIds, VisibilityEnum target, Long operatorId) {
        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }
        // 逐篇独立事务：单篇失败不中断批次（void 无回执，失败篇目日志留痕）
        for (Long articleId : articleIds) {
            try {
                transactionTemplate.executeWithoutResult(status -> doChangeVisibility(articleId, target, operatorId));
            } catch (BizException e) {
                log.warn("批量改可见性单篇失败，articleId={}，target={}，operatorId={}，errorCode={}",
                        articleId, target, operatorId, e.getCode());
            }
        }
    }

    /**
     * 发布前预检（DFA 闸门）：仅返回 {hit, risk} 不回吐命中词（防词库 oracle 泄露）；
     * 预检不替代发布闸门——发布同步段仍独立执行同口径 DFA（双入口封堵试探）
     *
     * @param dto    预检入参（标题/正文）
     * @param userId 当前登录用户ID
     * @return 预检结果（命中标记+风险级别）
     */
    @Override
    public ContentPreviewCheckVO previewCheck(ContentPreviewCheckDTO dto, Long userId) {
        // 场景校验：仅文本三场景开放（USERNAME 注册即终态无预检路径；图片场景 normalize=null 拒绝）
        String normalized = DfaScenes.normalize(dto.getScene());
        if (normalized == null || DfaScenes.USERNAME.equals(normalized)) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 预检文本=标题+分隔符+正文（拼接口径同 SubmitTextAssembler，与发布闸门同源防漂移）
        String assembledText = SubmitTextAssembler.assemble(dto.getTitle(), dto.getContent());
        // 同一 DfaFacade 快照判定（同 scene 同词库版本）；空文本自然 noHit
        DfaHitResult hitResult = dfaFacade.check(normalized, assembledText);
        // 禁回吐命中词：仅映射 {hit, risk}（risk 取 DFA_LEVEL_HIGH/MID，口径同 VO 注释）
        ContentPreviewCheckVO vo = new ContentPreviewCheckVO();
        vo.setHit(hitResult.hit());
        vo.setRisk(hitResult.maxLevel());
        return vo;
    }

    /**
     * 密码访问校验（[4.2]）：BCrypt 比对不回显；失败限频防爆破对齐 auth 登录口径
     * （LoginRetryLimiter：连续 5 次失败锁 60s，锁定中直接抛 10005）；校验通过写短时效
     * 凭证标记（content:pwd-pass:{operatorId}:{articleId}，TTL 10min），getDetail PASSWORD
     * 分支命中标记放行正文——判定链仍收口 getDetail，本方法不回吐正文
     *
     * @param articleId 文章ID
     * @param password  用户输入密码（明文，不落日志）
     * @param operatorId 当前登录用户ID（防爆破维度）
     */
    @Override
    public void verifyPassword(Long articleId, String password, Long operatorId) {
        // 防爆破闸口前置：锁定中直接拒绝（超限 TOO_MANY_REQUESTS=10005，不再暴露密码错误细节）
        String retryIdentifier = ContentBizConstant.PASSWORD_RETRY_IDENTIFIER_PREFIX + operatorId;
        if (loginRetryLimiter.isLocked(retryIdentifier)) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        String encoded = article.getAccessPassword();
        // 未设密码与密码错误统一按密码错误处理（不暴露配置状态）；BCrypt 比对不回显明文；
        // 失败计数入 LoginRetryLimiter（连 5 次锁 60s）+ Controller @RateLimiter 双层防爆破
        if (password == null || encoded == null || encoded.isBlank()
                || !passwordCrypto.matches(password, encoded)) {
            loginRetryLimiter.recordFailure(retryIdentifier,
                    ContentBizConstant.PASSWORD_RETRY_MAX_ATTEMPTS, ContentBizConstant.PASSWORD_LOCK_SECONDS);
            throw new BizException(ErrorCode.ARTICLE_PASSWORD_ERROR);
        }
        // 比对成功：重置失败计数 + 写密码凭证标记（getDetail PASSWORD 分支 10min 内放行正文）
        loginRetryLimiter.resetFailure(retryIdentifier);
        stringRedisTemplate.opsForValue().set(
                ContentBizConstant.PWD_PASS_MARK_KEY_PREFIX + operatorId + ":" + articleId,
                "1", Duration.ofSeconds(ContentBizConstant.PWD_PASS_MARK_TTL_SECONDS));
    }

    /**
     * 单篇送审（事务体内执行）：归属 → 幂等跳过 → 状态机预校验 → CAS 0→1 回填批次ID →
     * afterCommit 发 content.submitted（事件体不含正文，review 经 ContentClient 回查）
     */
    private void doPublishSingle(Long articleId, Long operatorId, Long batchId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // 归属校验：批量发布仅限本人文章（对齐单篇发布 owner 语义）
        if (!article.getAuthorId().equals(operatorId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // 已发布幂等跳过（重复提交无副作用，回执成功）
        if (article.getStatus() == ArticleStatusEnum.PUBLISHED) {
            return;
        }
        // 状态机白名单预校验（0→1 允许；驳回重提走 /submit 专口，待审/下架不入批量）
        if (!articleStateMachine.canTransit(article.getStatus(), ArticleStatusEnum.PENDING_REVIEW)) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // contentHash 缺失防御：无指纹无法送审（review 按 hash 留痕比对），fail-fast 该篇失败
        if (article.getContentHash() == null || article.getContentHash().isBlank()) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // CAS 0→1（并发兜底 where status=0，MP 自动拼 del_flag=0）+ 回填批次ID
        int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getStatus, ArticleStatusEnum.DRAFT)
                .set(Article::getStatus, ArticleStatusEnum.PENDING_REVIEW)
                .set(Article::getPublishBatchId, batchId));
        if (rows == 0) {
            // CAS 未命中=并发已流转（如已被单篇发布）→ 幂等跳过
            return;
        }
        // afterCommit 发 content.submitted（事务顺序铁律：禁本地事务内发送）
        TextSubmitEvent event = new TextSubmitEvent();
        event.setModType(ModerationConstant.MOD_TYPE_AUTO);
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(articleId);
        event.setScene(DfaScenes.ARTICLE);
        event.setContentHash(article.getContentHash());
        event.setRetryCount(0);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contentEventProducer.sendSubmitted(event);
            }
        });
    }

    /**
     * 单篇改可见性（事务体内执行）：归属 → 幂等 → CAS 变更 →
     * 转私密（已发布态）afterCommit 必发 content.unpublished（隐私红线防残留）
     */
    private void doChangeVisibility(Long articleId, VisibilityEnum target, Long operatorId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // 批量改可见性仅 owner（可见性属作者个人隐私决定，ADMIN 不代改）
        if (!article.getAuthorId().equals(operatorId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // 无变化幂等跳过
        if (article.getVisibility() == target) {
            return;
        }
        // 批量转密码访问缺密码入参 → 该篇失败（密码只能单篇设置，防弱密码批量扩散）
        if (target == VisibilityEnum.PASSWORD) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // CAS 变更（where visibility=旧值，并发兜底）；转公开（→1）限定 status=2：
        // 仅已发布文章可对外公开（未过审内容禁公开——审核红线）；
        // content_hash 未变（方案B 禁改已发布正文）→ 原审核结论仍有效，不重审
        LambdaUpdateWrapper<Article> wrapper = new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getVisibility, article.getVisibility())
                .set(Article::getVisibility, target);
        if (target == VisibilityEnum.PUBLIC) {
            wrapper.eq(Article::getStatus, ArticleStatusEnum.PUBLISHED);
        }
        int rows = articleMapper.update(null, wrapper);
        if (rows == 0) {
            // CAS 未命中=并发已变更 → 幂等跳过
            return;
        }
        // 转私密（已发布态 → PRIVATE）对齐单篇 update 口径：afterCommit 必发 content.unpublished；
        // 转公开（→PUBLIC）不重审（content_hash 未变原审核结论仍有效）：afterCommit 发 content.published
        // （[4.2] 补契约后接通；不重建/不重写任何审核缓存，原键仍有效）
        boolean toPrivate = target == VisibilityEnum.PRIVATE
                && article.getStatus() == ArticleStatusEnum.PUBLISHED
                && article.getVisibility() != VisibilityEnum.PRIVATE;
        if (target == VisibilityEnum.PUBLIC) {
            ContentPublishedEvent publishedEvent = new ContentPublishedEvent();
            publishedEvent.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
            publishedEvent.setBizId(articleId);
            publishedEvent.setContentHash(article.getContentHash());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    contentEventProducer.sendPublished(publishedEvent);
                }
            });
        }
        if (toPrivate) {
            // 批量转私密同样收口下架缓存失效（[3.4] 方案 A，fail-safe 不阻断批量主流程）
            unpublishedCacheCleaner.invalidateArticle(articleId, article.getContentHash());
            ContentUnpublishedEvent event = new ContentUnpublishedEvent();
            event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
            event.setBizId(articleId);
            event.setContentHash(article.getContentHash());
            event.setReason(ContentBizConstant.UNPUBLISH_REASON_TO_PRIVATE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    contentEventProducer.sendUnpublished(event);
                }
            });
        }
    }
}