package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 文章发布服务实现类（§6.1 同步段 A1→A3 / §6.3 批量 / 预检）。
 * <p>阶段9：密码可见（PASSWORD/4）功能已下线，verifyPassword 链路整体删除</p>
 *
 * 【发布编排】点"发布"后同步只做三件事：基础校验 → 满足免审条件的直接发布 →
 * 其余的改状态为"待审核"并通知审核系统，不同步等审核结果；
 * 敏感词切片、聚合判定、结论回写都在审核消费端完成，这里不重复做（防止两边同时写打架）。
 * 【消息发送时机】先等数据库事务提交成功，再发消息（事务回滚了消息却已发出会留脏数据）。
 * 【批量发布】单批数量有上限（防批量刷审）；每篇文章单独一个事务，
 * 一篇失败不影响其他篇，逐篇返回成功/失败回执，失败的可以单独重提。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticlePublishServiceImpl implements ArticlePublishService {

    private final ArticleMapper articleMapper;

    private final ContentEventProducer contentEventProducer;

    private final ArticleStateMachine articleStateMachine;

    /** DFA 同步闸门门面（宿主本地 Bean，预检与发布闸门同源同快照） */
    private final DfaFacade dfaFacade;

    private final ContentProperties contentProperties;

    /** 编程式事务（逐篇独立事务边界；Boot 自动装配，同一 PlatformTransactionManager） */
    private final TransactionTemplate transactionTemplate;

    /** 下架审核缓存失效编排（[3.4] 方案 A：last-hash 精确删 + text/img-result SCAN 通配版本段） */
    private final UnpublishedCacheCleaner unpublishedCacheCleaner;

    /**
     * 批量发布文章。
     *
     * 作用：
     * 校验单批数量上限（≤ batch-max，防批量刷审）后，将一批草稿文章逐篇送审。
     * 每篇文章在独立事务中执行"归属校验 → 幂等跳过 → 状态机校验 → CAS 改状态 →
     * 发审核事件"，单篇失败不回滚其他篇目，最终逐篇返回成功/失败回执；
     * 已发布的文章重复提交时自动跳过（幂等，回执成功）。
     *
     * @param articleIds 待发布文章ID列表（单批数量不可超过 batch-max 配置）
     * @param operatorId 操作人ID（当前登录用户，仅能发布本人文章）
     * @return 逐篇发布结果回执（articleId + success + 失败时的 errorCode）
     */
    @Override
    public List<ArticleBatchResultVO> publishBatch(List<Long> articleIds, Long operatorId) {

        /**
         * 批量发布流程：
         *
         * ① 数量上限校验：一批最多 batch-max 篇，超限整体拒绝（防批量刷审核）。
         * ② 生成批次ID：同一批文章共享一个批次号，方便批量展示与操作定位。
         * ③ 逐篇发布：每篇文章在独立事务里走“校验→改状态→发事件”；
         *    单篇失败不影响其他篇，最后逐篇返回成功/失败回执，失败篇目可单独重提。
         */

        // 步骤1：单批数量上限校验：超过配置上限整批拒绝（防止有人批量刷审核）
        if (articleIds != null && articleIds.size() > contentProperties.getPublish().getBatchMax()) {
            throw new BizException(ErrorCode.ARTICLE_BATCH_EXCEEDED);
        }
        List<ArticleBatchResultVO> results = new ArrayList<>();
        if (articleIds == null || articleIds.isEmpty()) {
            return results;
        }
        // 步骤2：生成批次ID：同批文章都记这个号，方便按批次展示和批量操作
        Long batchId = IdWorker.getId();
        // 步骤3：逐篇发布：每篇文章在自己的事务里执行，一篇失败不影响其他篇
        for (Long articleId : articleIds) {
            try {
                //transactionTemplate.executeWithoutResult:如果抛异常，事务自动回滚；如果正常结束，事务自动提交。
                transactionTemplate.executeWithoutResult(status -> doPublishSingle(articleId, operatorId, batchId));
                results.add(new ArticleBatchResultVO().setArticleId(articleId).setSuccess(true));
            } catch (BizException e) {
                // 失败篇目在回执里记录错误码（已成功的篇目不受影响）
                log.warn("批量发布单篇失败，articleId={}，operatorId={}，errorCode={}",
                        articleId, operatorId, e.getCode());
                results.add(new ArticleBatchResultVO().setArticleId(articleId)
                        .setSuccess(false).setErrorCode(String.valueOf(e.getCode())));
            }
        }
        return results;
    }

    /**
     * 批量修改文章可见性。
     *
     * 作用：
     * 逐篇将文章可见性改为目标值，每篇在独立事务中执行"归属校验 → 幂等跳过 →
     * CAS 变更 → 按需发送上/下架事件"，单篇失败不中断批次（void 无回执，日志留痕）。
     * 转公开仅允许已过审文章（status=2）且不重审；转私密触发下架缓存失效并发送
     * content.unpublished 事件（防缓存残留）。
     *
     * @param articleIds 待修改文章ID列表
     * @param target     目标可见性（PUBLIC/PRIVATE/FOLLOWERS；阶段9 密码可见已下线）
     * @param operatorId 操作人ID（仅能修改本人文章）
     */
    @Override
    public void batchVisibility(List<Long> articleIds, VisibilityEnum target, Long operatorId) {

        /**
         * 批量改可见性流程：
         *
         * ① 空列表直接返回（无待处理篇目）。
         * ② 逐篇独立事务：每篇走“归属校验→幂等跳过→CAS 改可见性→按需发上/下架事件”；
         *    单篇失败只记日志，不中断整批。
         */

        // 步骤1：空列表直接返回（没有要处理的文章）
        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }
        // 步骤2：逐篇独立事务执行：一篇失败只记日志，不中断整批（本接口无逐篇回执）
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
     * 发布前敏感词预检（DFA 闸门）。
     *
     * 作用：
     * 对用户输入的标题+正文做 DFA 敏感词预检查，返回是否命中及最高风险级别，
     * 供前端提前提示用户修改内容。预检结果不作为最终审核结论，发布时仍会
     * 独立执行同口径 DFA 检查（双入口封堵绕过预检直接发布的试探）；
     * 出于防词库泄露考虑，不返回具体命中词。
     *
     * @param dto    预检入参（scene 场景、title 标题、content 正文）
     * @param userId 当前登录用户ID
     * @return 预检结果（hit 是否命中 + risk 最高风险级别）
     */
    @Override
    public ContentPreviewCheckVO previewCheck(ContentPreviewCheckDTO dto, Long userId) {

        /**
         * 发布前预检流程（DFA 敏感词预检查）：
         *
         * ① 场景校验：仅开放文本三场景（注册用户名无预检路径，图片场景拒绝）。
         * ② 拼接文本：标题+分隔符+正文，与发布闸门同口径（防预检与实际审核结果漂移）。
         * ③ 敏感词扫描：同一 DFA 词库快照判定（同场景同版本）。
         * ④ 返回结果：只回“是否命中+最高风险级别”，不回具体命中词（防词库被试探泄露）。
         */

        // 步骤1：场景校验：只开放文本类场景（用户名注册没有预检入口；图片场景不支持文本预检）
        String normalized = DfaScenes.normalize(dto.getScene());
        if (normalized == null || DfaScenes.USERNAME.equals(normalized)) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 步骤2：拼接待检文本（标题+分隔符+正文），拼法和正式发布时完全一样，
        // 保证"预检说没问题"和"发布时真没问题"是同一个判断标准
        String assembledText = SubmitTextAssembler.assemble(dto.getTitle(), dto.getContent());
        // 步骤3：敏感词扫描（和发布闸门用同一份词库）；空文本自然不命中
        DfaHitResult hitResult = dfaFacade.check(normalized, assembledText);
        // 步骤4：组装返回结果：只告诉前端"有没有命中+风险级别"，不返回具体命中词（防止词库被试探摸出来）
        ContentPreviewCheckVO vo = new ContentPreviewCheckVO();
        vo.setHit(hitResult.hit());
        vo.setRisk(hitResult.maxLevel());
        return vo;
    }

    /**
     * 单篇发布（送审），在 publishBatch 的逐篇事务内执行。
     *
     * 作用：
     * 按顺序执行：存在性校验 → 归属校验（仅本人）→ 已发布幂等跳过 →
     * 状态机预校验（仅草稿可送审）→ contentHash 防御校验 → CAS 更新状态 0→1
     * 并回填批次ID → 事务提交后发送 content.submitted 审核事件。
     * 任一校验失败抛 BizException，由调用方记录失败回执。
     *
     * @param articleId  待发布文章ID
     * @param operatorId 操作人ID（当前登录用户，仅能发布本人文章）
     * @param batchId    批次ID（同批文章共享，用于批量展示与操作定位）
     */
    private void doPublishSingle(Long articleId, Long operatorId, Long batchId) {

        /**
         * 单篇发布流程（在  **批量发布**  的逐篇事务内执行）：
         *
         * ① 查文章，不存在报错。
         * ② 归属校验：只能发布自己的文章。
         * ③ 已发布的重复提交直接跳过（幂等，回执成功）。
         * ④ 状态机校验：只有草稿能送审（驳回重提走专口，待审/下架不入批量）。
         * ⑤ 内容指纹防御：没指纹没法送审，直接失败。
         * ⑥ CAS 改状态 0→1 并记批次ID；并发冲突时视为已被处理，幂等跳过。
         * ⑦ 事务提交后发 content.submitted（事务内禁发消息铁律）。
         */

        // step1：查询文章，不存在直接报错   步骤5解决逻辑删除的cas，此外，mp自动过滤逻辑删除-yml配置
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // step2：归属校验：只能发布自己的文章
        if (!article.getAuthorId().equals(operatorId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // step3：已经是发布状态的重复提交直接跳过（幂等，回执算成功）
        if (article.getStatus() == ArticleStatusEnum.PUBLISHED) {
            return;
        }
        // step4：状态校验：只有草稿能走批量送审（被驳回的文章要走专门的"重新提交"接口，待审/已下架的不允许）
        if (!articleStateMachine.canTransit(article.getStatus(), ArticleStatusEnum.PENDING_REVIEW)) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // step5：内容指纹缺失直接失败：审核端靠指纹留痕比对，没有指纹没法送审
        if (article.getContentHash() == null || article.getContentHash().isBlank()) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // step6：把状态从"草稿"改成"待审核"并记录批次ID；
        // 更新条件里带上"当前还是草稿"——两人同时发同一篇时只有一个人改得动，另一个视为已被处理
        int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getStatus, ArticleStatusEnum.DRAFT)
                .set(Article::getStatus, ArticleStatusEnum.PENDING_REVIEW)
                .set(Article::getPublishBatchId, batchId));
        if (rows == 0) {
            // CAS 未命中=并发已流转（如已被单篇发布）→ 幂等跳过
            return;
        }

        // step7：组装审核通知消息，登记到"事务提交成功后"再发送
        // （事务还没提交就发消息，一旦回滚消息收不回来；消息里不带正文，审核端收到后自己来查）
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
     * 单篇修改可见性，在 batchVisibility 的逐篇事务内执行。
     *
     * 作用：
     * 按顺序执行：存在性校验 → 归属校验（仅本人）→ 无变化幂等跳过 →
     * CAS 变更可见性（并发兑底）。
     * 转公开（→PUBLIC）限定 status=2（仅已过审文章可公开）且不重审；
     * 已发布文章转私密时，事务提交后先触发下架缓存失效，再发送 content.unpublished 事件。
     *
     * @param articleId  待修改文章ID
     * @param target     目标可见性
     * @param operatorId 操作人ID（仅能修改本人文章）
     */
    private void doChangeVisibility(Long articleId, VisibilityEnum target, Long operatorId) {

        /**
         * 单篇改可见性流程（在批量改可见性的逐篇事务内执行）：
         *
         * ① 查文章，不存在报错。
         * ② 归属校验：可见性属作者个人隐私决定，仅本人可改（ADMIN 不代改）。
         * ③ 目标值与现值相同直接跳过（幂等）。
         * ④ CAS 改可见性（并发冲突幂等跳过）；转公开额外要求“已过审”——
         *    未过审内容禁止公开（审核红线），且不重审（原审核结论仍有效）。
         * ⑤ 发事件：转公开发 content.published（重新收录）；已发布转私密则
         *    先失效审核缓存，再发 content.unpublished（防缓存/索引残留）。
         */

        // step1：查询文章，不存在直接报错
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // step2：归属校验：可见性是作者个人的隐私决定，只能本人改（管理员也不代改）
        if (!article.getAuthorId().equals(operatorId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // step3：目标可见性和现在一样就直接跳过（幂等）
        if (article.getVisibility() == target) {
            return;
        }
        // step4：修改可见性；更新条件带上"当前可见性=改之前的值"——两人同时改只有一人生效，另一个视为已被处理；
        // 转公开额外要求文章必须是"已发布"状态：没过审的内容禁止对外公开（审核红线）；
        // 正文内容没变过（已发布文章禁改正文），原来的审核结论依然有效，不需要重新审核
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
        // step6：准备后续要发的通知（都等事务提交成功后再发）：
        // 转公开 → 发"已发布"通知，让搜索/AI 重新收录（不重建审核缓存，原来的键还有效）；
        // 已发布的文章转私密 → 发"下架"通知，让搜索/AI 移除这篇内容
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
            // step7：转私密时顺带清掉这篇文章的审核缓存（防止缓存的旧"通过"结论残留）；
            // 清理失败只记日志，不影响批量主流程
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