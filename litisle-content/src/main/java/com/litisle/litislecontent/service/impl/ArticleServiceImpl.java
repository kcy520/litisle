package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.constant.PageConstant;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dto.file.FileOwnerDTO;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaHitResult;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litisleapi.event.content.ContentPublishedEvent;
import com.litisle.litisleapi.event.content.ContentUnpublishedEvent;
import com.litisle.litisleapi.event.moderation.ImageSubmitEvent;
import com.litisle.litisleapi.event.moderation.TextSubmitEvent;
import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litisleapi.moderation.DfaPublishGate;
import com.litisle.litisleapi.moderation.LastHashExemptChecker;
import com.litisle.litisleapi.moderation.LastHashStore;
import com.litisle.litisleapi.moderation.PublishTextPrecheck;
import com.litisle.litisleapi.moderation.SubmitTextAssembler;
import com.litisle.commoncore.result.PageResult;
import com.litisle.litislecontent.cache.FollowRelationCache;
import com.litisle.litislecontent.cache.UnpublishedCacheCleaner;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.dto.ArticleImageDTO;
import com.litisle.litislecontent.domain.dto.ArticlePublishDTO;
import com.litisle.litislecontent.domain.dto.ArticleMyQueryDTO;
import com.litisle.litislecontent.domain.dto.ArticlePublicQueryDTO;
import com.litisle.litislecontent.domain.dto.ArticleSaveDTO;
import com.litisle.litislecontent.domain.dto.ArticleUpdateDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleDraft;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.domain.router.ZoneRouter;
import com.litisle.litislecontent.domain.statemachine.ArticleStateMachine;
import com.litisle.litislecontent.enums.FavoriteTargetTypeEnum;
import com.litisle.litislecontent.service.support.FavoriteStateSupport;
import com.litisle.litislecontent.domain.vo.ArticleCardVO;
import com.litisle.litislecontent.domain.vo.ArticleDetailVO;
import com.litisle.litislecontent.domain.vo.ArticleImageVO;
import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import com.litisle.litislecontent.mapper.ArticleDraftMapper;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mq.ContentEventProducer;
import com.litisle.litislecontent.service.ArticleService;
import com.litisle.litislecontent.service.support.ArticleImageRefSupport;
import com.litisle.litislecontent.service.support.ArticleImageRenderSupport;
import com.litisle.litislecontent.service.support.PublishDraftSnapshotSupport;
import com.litisle.litislecontent.utils.LogSanitizeUtil;
import com.litisle.litislecontent.utils.ArticleHtmlSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import java.util.stream.Collectors;

/**
 * 文章域实现（CRUD/详情可见性判定链/软删/公开读）。
 * 
 * 作用：
 * 文章发布与可见性编排服务，负责发布/批量发布、敏感词预检和可见性变更，
 * 保证"先提交事务、后发消息"的顺序，重复执行无副作用，并发改状态都带"当前必须是什么状态"的条件。
 *
 * 【可见性判定链（§6.4）】getDetail 按 visibility 收口（阶段9：密码访问 4 已下线）：
 *   1-公开放行；2-仅自己（owner 校验，非本人按不存在处理，不暴露存在性）；
 *   3-仅关注者（回查 social 关注关系，降级=按非关注者处理——宁严勿松，结果加短 TTL 缓存±10%抖动）
 * 【软删红线】deleteById 必发 content.unpublished（四路径统一收口之一，防向量库/索引残留）
 * 【大字段延迟加载】列表查询不带 content_html，详情页单独取
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    /** 文章 Mapper（文章 CRUD/列表数据源） */
    private final ArticleMapper articleMapper;

    /** 文章配图 Mapper（配图挂载/详情回显反查数据源） */
    private final ArticleImageMapper articleImageMapper;

    /** 草稿快照 Mapper（阶段6：发布归属切换时草稿归属校验数据源） */
    private final ArticleDraftMapper articleDraftMapper;

    /** 文件服务 Feign 契约（插图归属校验/批量取 sha256/批量取 URL） */
    private final FileClient fileClient;

    /** 文章/草稿插图引用支撑组件（阶段6：批量归属校验 + file_ref bind/unbind 统一收口，消除双份重复实现） */
    private final ArticleImageRefSupport articleImageRefSupport;

    /** 正文配图占位符渲染支撑组件（文章详情/草稿详情共用：fileId 批量换 URL + 占位符替换 <img>，消除双份重复实现） */
    private final ArticleImageRenderSupport articleImageRenderSupport;

    /** 内容事件生产者（submitted/published/unpublished/image.submit 事件发送） */
    private final ContentEventProducer contentEventProducer;

    /** 文章状态机（送审/重提流转白名单预校验） */
    private final ArticleStateMachine articleStateMachine;

    /** DFA 同步闸门门面（宿主本地 Bean，发布链路零网络） */
    private final DfaFacade dfaFacade;

    /** last-hash Redis 读写适配（契约层 RedisAdapter 宿主注入，免审判定用） */
    private final LastHashStore.RedisAdapter lastHashRedisAdapter;

    /** 分区路由权限校验（[3.3] 单点收口：发布/编辑分区变更统一走本组件） */
    private final ZoneRouter zoneRouter;

    /** 下架审核缓存失效编排（[3.4] 方案 A：last-hash 精确删 + text/img-result SCAN 通配版本段） */
    private final UnpublishedCacheCleaner unpublishedCacheCleaner;

    /** 关注关系回查缓存（[4.1] visibility=3：短 TTL 缓存 + miss 回查 SocialClient，fail-fast） */
    private final FollowRelationCache followRelationCache;

    /** 收藏状态查询组件（getDetail 末尾回填 favorited 字段，阶段16） */
    private final FavoriteStateSupport favoriteStateSupport;

    /** 发布入口草稿快照组件（P1 发布异常保稿：校验前 UPSERT 快照进 article_draft，REQUIRES_NEW 独立事务） */
    private final PublishDraftSnapshotSupport publishDraftSnapshotSupport;

    /**
     * 创建并发布（§2.4 发布编排）：A0 分区权限 → A1 图文同空校验+参数校验 → 内容清洗 →
     * 敏感词同步扫描（严重词拦 70001 / 观察词仅留痕）→ 内容指纹落库（status=1 待审）→ 配图挂到文章下 →
     * A2 免审判定三分支（直接通过 / 提示驳回 / 正常送审）→ A3 事务提交后发 content.submitted
     *
     * @param dto      发布入参（标题/正文/配图/分区/可见性等）
     * @param authorId 作者用户ID（插图归属校验基准）
     * @return 新建文章ID
     * @throws BizException CONTENT_EMPTY 图文同空 / ARTICLE_PARAM_INVALID 参数非法 / ARTICLE_IMG_REFUSED 插图越权
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAndPublish(ArticlePublishDTO dto, Long authorId) {



        /**
         * 文章发布流程：
         *
         * ① 分区权限检查
         *    不同分区有不同的门槛（作家区要作者身份、运营区要管理员），没权限直接拒绝。
         *
         * ② 内容合规校验
         *    图文不能都为空；有正文就校验标题长度、正文是否有实质内容；
         *    有配图就逐张检查是不是你自己上传的（防止引用别人的文件）；
         *    封面图也做同样的归属检查，防止挂别人的图当封面。
         *
         * ③ 内容清洗（防XSS）
         *    标题和摘要剥掉所有HTML标签变成纯文本；正文只保留白名单内的安全标签（脚本/危险属性全部过滤），占位符不受影响。
         *
         * ④ 敏感词扫描
         *    所有分区统一走机审：命中严重敏感词直接拒绝发布（错误码70001）；
         *    命中轻微观察词只记日志留痕，不拦截发布。
         *
         * ⑤ 落库（状态=待审核）
         *    用"标题+正文"算一个内容指纹（contentHash），方便后续判断内容是否改动过；
         *    文章状态标记为"待审核"，插入数据库拿到文章ID。
         *
         * ⑥ 配图与封面绑定（含旧图清理）
         *    如果是从草稿发布的：对比草稿里的旧图和本次新选的图，只解绑那些"不再使用"的旧图，
         *    继续沿用的图不解绑（差集口径，避免误伤）；
         *    本次新选的配图逐张挂到文章ID下；
         *    封面图也登记绑定到这篇文章；
         *    如果换了新封面，旧草稿封面引用也会被解绑清理。
         *    → 所有绑定操作在同一事务内，任一步失败立即整体回滚。
         *
         * ⑦ 免审判定（三分支）
         *    - 直接通过：内容没改过 + 图片没换过 + 敏感词库没升级过 + 上次审核是通过状态
         *      → 直接把状态从"待审核"改成"已发布"（CAS保证并发安全），不发审核通知，
         *        事务提交后通知搜索引擎和AI向量库"这篇可以直接展示了"。
         *    - 提示驳回：内容没改但上次审核是驳回状态
         *      → 直接回滚事务，告诉用户"内容没改还是会被驳回，请修改后再发"，省去一次注定失败的机审。
         *    - 正常送审：以上都不满足（内容改了/图换了/词库升级了/第一次发）
         *      → 保持"待审核"状态，走第⑧步。
         *    ※ 纯图文章直接走正常送审，不参与免审判定。
         *    ※ "免文不免图"原则：文字可以免审，但图片只要换了就必须重新送审。
         *
         * ⑧ 送审通知（仅正常送审分支）
         *    等数据库事务提交成功后，发消息通知审核系统"有新文章待审"；
         *    如果有配图，额外发一条图片复审通知（附带图片聚合指纹，审核端会校验一致性）。
         *    → 事务内不发送消息（铁律），防止事务回滚后消息已发出导致脏数据。
         */


        // 步骤A-（P1 发布异常保稿，方案B）：发布请求携带正文 → 所有校验前先把内容快照进 article_draft 单行表，
        // 后续任何一步失败（图文校验/图片归属/敏感词/CAS）本次内容必已在草稿，用户不丢稿；
        // REQUIRES_NEW 独立事务保证主链路失败回滚不连带快照回滚；fail-safe：快照失败仅 warn 不阻断发布主链路；
        // snapshotDraftId=本次快照实际写入的草稿行ID（null=快照失败），发布建档成功后用于回填 article_id
        Long snapshotDraftId = null;
        try {
            snapshotDraftId = publishDraftSnapshotSupport.snapshotForPublish(dto, authorId);
        } catch (Exception e) {
            log.warn("发布入口草稿快照失败（不阻断发布主链路）：authorId={}，draftId={}",
                    authorId, dto.getDraftId(), e);
        }

        // 步骤A0：分区权限（ZoneRouter 单点收口 [3.3]：zone=2 需 AUTHOR；默认普通分区）
        ZoneEnum zone = dto.getZone() == null ? ZoneEnum.NORMAL : dto.getZone();
        zoneRouter.checkPublishPermission(zone);

        // 步骤A1：图文同空校验——正文没有可见文字且没有配图时直接拒绝（纯图文章视为有内容，不走文字校验）
        boolean hasText = PublishTextPrecheck.hasVisibleText(dto.getContentHtml());
        boolean hasImg = dto.getImages() != null && !dto.getImages().isEmpty();
        if (!hasText && !hasImg) {
            throw new BizException(ErrorCode.CONTENT_EMPTY);
        }

        if (hasText) {
            // 契约门面前置校验（标题 trim 非空/≤100 + 去标签可见文本非空；先于 contentHash 计算与落库）
            PublishTextPrecheck.check(dto.getTitle(), dto.getContentHtml());
        }

        // 插图归属校验：fileId 归属本人且 bizType=ARTICLE_IMG（防引用他人文件/违规图）
        if (hasImg) {
            checkImageOwnership(dto.getImages(), authorId);
        }
        // 阶段8② 封面归属校验补口：coverFileId 可挂他人文件当封面的漏洞收口（阶段6 只查正文 images）
        articleImageRefSupport.checkCoverOwnership(dto.getCoverFileId(), authorId);

        // 内容清洗：标题/摘要剥掉所有标签变纯文本；正文按白名单过滤危险标签后入库（图片占位符原样保留）
        String title = ArticleHtmlSanitizer.stripTags(dto.getTitle());
        String summary = ArticleHtmlSanitizer.stripTags(dto.getSummary());
        String contentHtml = hasText ? ArticleHtmlSanitizer.sanitize(dto.getContentHtml()) : dto.getContentHtml();

        // 敏感词同步扫描（§4.2.1 ①，所有分区统一走机审）：命中黑名单词拦截、命中观察词只记日志
        String assembledText = SubmitTextAssembler.assemble(title, contentHtml == null ? "" : contentHtml);
        if (hasText) {
            DfaHitResult hitResult = dfaFacade.check(DfaScenes.ARTICLE, assembledText);
            DfaPublishGate.DfaGateDecision gate = DfaPublishGate.decide(DfaScenes.ARTICLE, hitResult);
            if (gate.blocked()) {
                // HIGH（黑名单词）同步拦截，错误码统一 70001
                throw new BizException(ErrorCode.SENSITIVE_WORD_DETECTED);
            }
            if (gate.midMark()) {
                // MID（观察词）仅标记不拦截：记一条日志留痕，送审后的最终处理由 review 审核端决定
                log.info("DFA MID 命中仅标记不拦截：authorId={}，wordlibVer={}", authorId, hitResult.wordlibVer());
            }
        }

        // 落库主表：contentHash=SHA256(标题+\n+净化后正文)，与 review 端重算指纹用同一套规则
        Article article = new Article()
                .setAuthorId(authorId)
                .setZone(zone)
                .setTitle(title)
                .setSummary(summary)
                .setContentHtml(contentHtml)
                .setCoverFileId(dto.getCoverFileId())
                // 分类不传时缺省「散文」（与数据库默认值、BookServiceImpl 的缺省规则保持一致；仅新建时生效）
                .setCategory(dto.getCategory() == null ? ArticleCategoryEnum.DEFAULT : dto.getCategory())
                .setStatus(ArticleStatusEnum.PENDING_REVIEW)
                .setVisibility(dto.getVisibility())
                .setViewCnt(0L).setLikeCnt(0L).setCommentCnt(0L)
                .setContentHash(SubmitTextAssembler.contentHash(assembledText));
        articleMapper.insert(article);
        // 步骤A+（P1 发布回填）：快照草稿行存在 → 回填 article_id，建立草稿与已发布文章的关联标识
        // （草稿行保留；前端可据 article_id 展示「已发布」或过滤；直发场景快照新建的兜底行不再游离）。
        // 回填在主事务内：后续任一步失败（免审提示/配图登记/CAS 并发）事务回滚连带回填回滚，
        // 草稿行回到未关联状态——保证「仅发布成功才带已发布标识」，与 REQUIRES_NEW 已提交的快照内容互不影响
        if (snapshotDraftId != null) {
            articleDraftMapper.updateById(new ArticleDraft()
                    .setId(snapshotDraftId)
                    .setArticleId(article.getId()));
        }
        //  发布归属切换（阶段6，决策 D2）：dto.draftId 非空时删草稿维度的旧图行，事务提交后解绑草稿引用
        // （旧列表减新列表：还在沿用的图不解绑；须在 saveArticleImages 前执行，避免误伤同事务新插的文章图行）
        if (hasImg) {
            switchDraftImageOwnership(dto.getDraftId(), dto.getImages(), authorId);
        }
        // 配图落 article_image（发布即挂到文章ID下）+ 同一事务内逐张登记文件引用（有问题立刻报错）
        if (hasImg) {
            saveArticleImages(dto.getImages(), article.getId(), null, authorId);
        }
        // 阶段8② 封面引用登记：同一事务内把封面登记到文章ID下（有问题立刻报错，与正文配图同一链路）
        articleImageRefSupport.bindSingleRef(dto.getCoverFileId(),
                ContentBizConstant.FILE_BIZ_TYPE_COVER, article.getId(), authorId);
        // 发布归属切换联动（阶段8②）：dto.draftId 非空且草稿封面与新封面不同时，
        // 事务提交后解绑草稿维度的旧封面引用（沿用同一个封面就不解绑）
        //解绑草稿的封面引用
        unbindDraftCoverIfReplaced(dto.getDraftId(), dto.getCoverFileId(), authorId);
        // 步骤A2：免审判定三分支（§4.2.1 ②，纯图文章不判定直接送审；
        // 含图文章还要连带校验图片（4-3.4）：把全部配图指纹排序拼接再整体哈希，免审记录存在 + 指纹一致 + 上次结论是通过，
        // 三者齐备才免审——缺任何一个都照常送审（文字可以免审，图片换过必须重审，宁多审不漏审；
        // 新文章首次发布没有历史记录，自然走送审）；文章必须处于"待审"而非驳回/下架状态、且未删除，才可能免审）

        // ① 获取当前 DFA 词库版本号（用于判断「上次审核时的词库」是否还有效）
        // 如果词库升级过，即使内容 hash 一样，也不能直接免审（新词可能命中）
        long wordlibVer = dfaFacade.currentVersion(DfaScenes.ARTICLE);

        // ② 计算配图聚合指纹（用于「免文不免图」判定）
            // - 有图：按 fileId 顺序取 sha256 → 字典序排序拼接 → 再 SHA-256
            // - 无图：null（后续判定会跳过图片复审分支）
        String aggregatedImgHash = hasImg ? aggregatedImgHash(dto.getImages()) : null;

        // ③ 核心判定：last-hash 免审三分支决策
            // 触发条件：有正文（hasText），纯图文章直接走正常送审（SUBMIT）
        LastHashExemptChecker.ExemptDecision exempt;

            if (hasText) {
                // 有正文 → 进入 last-hash 免审判定
                boolean imgReReviewOk = !hasImg || LastHashExemptChecker.imgReReviewPassed(
                        wordlibVer, article.getId(), aggregatedImgHash, lastHashRedisAdapter);

                exempt = LastHashExemptChecker.decide(
                        wordlibVer,
                        ModerationConstant.BIZ_TYPE_ARTICLE,
                        DfaScenes.ARTICLE,
                        article.getId(),
                        article.getContentHash(),
                        ArticleStatusEnum.PENDING_REVIEW.getCode(),
                        0,
                        imgReReviewOk,
                        lastHashRedisAdapter
                );
            } else {
                // 纯图文章 → 直接走正常送审
                exempt = LastHashExemptChecker.ExemptDecision.SUBMIT;
            }


        if (exempt == LastHashExemptChecker.ExemptDecision.REJECT_HINT) {
            // 内容未修改仍将驳回：前置提示不落库不发 MQ（事务回滚连带 insert 回滚，省一次注定失败的机审）
            throw new BizException(ErrorCode.REVIEW_REJECTED);
        }

        // 免审直接发布：带"当前必须是待审状态"的条件把状态改成已发布（文章ID是先插入才拿到的，所以从待审改起），不发 submitted；
        if (exempt == LastHashExemptChecker.ExemptDecision.EXEMPT_PASS) {
            // "免文不免图"已在前面图片复审校验把关（能走到这里说明图片也已通过，无需再发图片送审消息，
            // 与 LastHashExemptChecker 约定一致：免审直落不发 submitted/image.submit）；
            // content.published 必须发（阶段10 修复：免审直落与机审通过后发同一事件，search 索引/ai 向量/notification 都靠它；
            // 免审直落不发 submitted → review 链路不会处理该文 → 不会与机审通过路径重复发送）
            int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, article.getId())
                    .eq(Article::getStatus, ArticleStatusEnum.PENDING_REVIEW)
                    .set(Article::getStatus, ArticleStatusEnum.PUBLISHED));
            // 并发兜底：状态没改成（已被并发请求变更）按参数非法拒绝，防止误报发布成功
            if (rows == 0) {
                throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
            }
            // 本地事务提交成功后再发 content.published（铁律：事务内不发消息，防回滚后消息已发出）
            publishPublishedAfterCommit(article);
            return article.getId();
        }

        // 步骤A3：照常送审——事务提交后发单条 content.submitted（事件体不含正文，review 端回查数据库拿内容）
        registerSubmitEventAfterCommit(article.getId(), article.getContentHash());
        // 含图文章发布复审（免文不免图）：afterCommit 发 image.submit（scene=ARTICLE_IMG_RE_REVIEW，
        // contentHash=配图聚合指纹；review 消费端校验指纹一致性，错配事件直接丢弃）
        if (hasImg) {
            registerImageSubmitEventAfterCommit(article.getId(), aggregatedImgHash);
        }
        // TODO(标签系统)：tagNames 挂载（≤5，池中无命中走 DFA 闸门受控新建），随板块三标签链路落地
        return article.getId();
    }

    /**
     * 文章建档（不发布，先建档攒批 → publishBatch 批量送审链路的上游入口）。
     *
     * 作用：
     * 与 createAndPublish 共享同一套加工链路（分区权限/图文校验/归属校验/净化/敏感词扫描/指纹/配图接线），
     * 差异仅在落库状态与事件：落库 status=DRAFT(0)（草稿态行不对外）、不发任何 MQ 事件
     * （送审事件由 publishBatch 的 doPublishSingle 在 0→1 后统一发送）。
     *
     * @param dto      建档入参
     * @param authorId 作者用户ID（插图归属校验基准）
     * @return 新建文章ID（status=0）
     * @throws BizException CONTENT_EMPTY 图文同空 / ARTICLE_PARAM_INVALID 参数非法 / SENSITIVE_WORD_DETECTED 命中黑名单词
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveDraftRow(ArticleSaveDTO dto, Long authorId) {

        /**
         * 文章建档流程（与发布同加工、异落库）：
         *
         * ① 分区权限检查（同发布 A0：zone=2 需 AUTHOR）。
         * ② 图文同空校验 + 文本契约校验（同发布 A1：正文与配图不可同时为空；标题 trim 非空 ≤100）。
         * ③ 归属校验：配图逐张校验本人文件；封面同款校验（防挂他人文件当封面）。
         * ④ 内容净化（防XSS）：标题/摘要剥标签，正文白名单过滤（与发布同一套规则，保证指纹同源）。
         * ⑤ 敏感词同步扫描：建档时即拦黑名单词（提前失败，避免攒批送审后逐篇注定被拒）。
         * ⑥ 落库 status=DRAFT(0)：contentHash 与发布同口径计算；visibility 缺省 PRIVATE（草稿态行不对外）。
         * ⑦ 配图/封面接线（与发布同款）：草稿归属切换 → 配图挂载 → 封面登记，全部同一事务。
         * ⑧ 不发任何事件：草稿态不对外（无需 published 收录）；送审事件由 publishBatch 0→1 后发送。
         */

        // 步骤①：分区权限（与发布同款，ZoneRouter 单点收口 [3.3]）
        ZoneEnum zone = dto.getZone() == null ? ZoneEnum.NORMAL : dto.getZone();
        zoneRouter.checkPublishPermission(zone);

        // 步骤②：图文同空校验 + 有正文时的文本契约校验（同发布 A1）
        boolean hasText = PublishTextPrecheck.hasVisibleText(dto.getContentHtml());
        boolean hasImg = dto.getImages() != null && !dto.getImages().isEmpty();
        if (!hasText && !hasImg) {
            throw new BizException(ErrorCode.CONTENT_EMPTY);
        }
        if (hasText) {
            PublishTextPrecheck.check(dto.getTitle(), dto.getContentHtml());
        }

        // 步骤③：归属校验（配图逐张 + 封面，防引用他人文件）
        if (hasImg) {
            checkImageOwnership(dto.getImages(), authorId);
        }
        articleImageRefSupport.checkCoverOwnership(dto.getCoverFileId(), authorId);

        // 步骤④：内容净化（与发布同一套规则，保证建档与发布的 contentHash 同源）
        String title = ArticleHtmlSanitizer.stripTags(dto.getTitle());
        String summary = ArticleHtmlSanitizer.stripTags(dto.getSummary());
        String contentHtml = hasText ? ArticleHtmlSanitizer.sanitize(dto.getContentHtml()) : dto.getContentHtml();

        // 步骤⑤：敏感词同步扫描（黑名单词建档即拦，观察词仅留痕——与发布闸门同口径）
        String assembledText = SubmitTextAssembler.assemble(title, contentHtml == null ? "" : contentHtml);
        if (hasText) {
            DfaHitResult hitResult = dfaFacade.check(DfaScenes.ARTICLE, assembledText);
            DfaPublishGate.DfaGateDecision gate = DfaPublishGate.decide(DfaScenes.ARTICLE, hitResult);
            if (gate.blocked()) {
                throw new BizException(ErrorCode.SENSITIVE_WORD_DETECTED);
            }
            if (gate.midMark()) {
                log.info("DFA MID 命中仅标记不拦截：authorId={}，wordlibVer={}", authorId, hitResult.wordlibVer());
            }
        }

        // 步骤⑥：落库（status=DRAFT 草稿态；visibility 缺省 PRIVATE——草稿态行不对外，送审通过后按建档值对外）
        Article article = new Article()
                .setAuthorId(authorId)
                .setZone(zone)
                .setTitle(title)
                .setSummary(summary)
                .setContentHtml(contentHtml)
                .setCoverFileId(dto.getCoverFileId())
                .setCategory(dto.getCategory() == null ? ArticleCategoryEnum.DEFAULT : dto.getCategory())
                .setStatus(ArticleStatusEnum.DRAFT)
                .setVisibility(dto.getVisibility() == null ? VisibilityEnum.PRIVATE : dto.getVisibility())
                .setViewCnt(0L).setLikeCnt(0L).setCommentCnt(0L)
                .setContentHash(SubmitTextAssembler.contentHash(assembledText));
        articleMapper.insert(article);

        // 步骤⑦：配图/封面接线（与发布同款，全部同一事务：任一步失败整体回滚）
        if (hasImg) {
            // 发布归属切换（阶段6）：先处理草稿维度的旧图行，再挂新文章图行（顺序同发布，防误伤）
            switchDraftImageOwnership(dto.getDraftId(), dto.getImages(), authorId);
            // 配图落 article_image + 同一事务内逐张登记文件引用（有问题立刻报错）
            saveArticleImages(dto.getImages(), article.getId(), null, authorId);
        }
        // 封面引用登记（阶段8②）：同一事务内把封面登记到文章ID下
        articleImageRefSupport.bindSingleRef(dto.getCoverFileId(),
                ContentBizConstant.FILE_BIZ_TYPE_COVER, article.getId(), authorId);
        // 换了封面才解绑草稿维度的旧封面引用（事务提交后执行，沿用同一个封面就不解绑）
        unbindDraftCoverIfReplaced(dto.getDraftId(), dto.getCoverFileId(), authorId);

        // 步骤⑧：不发任何事件（草稿态不对外；送审事件由 publishBatch 的 doPublishSingle 在 0→1 后发送）
        return article.getId();
    }

    /**
     * 作者侧"我的文章"分页列表（建档→攒批→批量送审闭环的挑选篇目入口）。
     *
     * 作用：
     * 按 author_id=当前登录人硬条件过滤，只返回本人文章；status 可选过滤
     * （含 status=0 建档草稿态行，供批量送审前挑选篇目拿 ID 列表）。
     *
     * @param query    查询条件（status/published 二选一/zone/category/keyword 可选；分页参数经 PageQuery 归一化）
     * @param authorId 当前登录作者ID
     * @return 分页卡片列表（不带正文大字段）
     */
    @Override
    public PageResult<ArticleCardVO> listMy(ArticleMyQueryDTO query, Long authorId) {

        /**
         * 我的文章列表流程：
         *
         * ① 组装查询条件：author_id=本人（硬条件）；status/zone/category 可空=不过滤；
         *    排除正文大字段（列表不加载 content_html，详情页才取）。
         * ② MyBatis-Plus 分页查询：分页兑底由 PageQuery 收口；排序走 sortBy 白名单
         *    （作者侧登录端点开放白名单内自定义排序，白名单外回退默认降序）。
         * ③ 封装 PageResult 返回（total/totalPages/hasNext 供前端分页组件）。
         */

        // 步骤1：查询条件：本人文章（硬条件）；状态筛选三态：status 单值优先（细粒度）→
        // published 二值（true=已发布 2；false=未发布 0/1/3/4 一把圈住）→ 都不传=全部状态；
        // keyword 标题模糊匹配（作者侧找回文章用，传了才拼）
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .select(Article.class, i -> !Article.COLUMN_CONTENT_HTML.equals(i.getColumn()))
                .eq(Article::getAuthorId, authorId)
                .eq(query.getStatus() != null, Article::getStatus, query.getStatus())
                .eq(query.getStatus() == null && Boolean.TRUE.equals(query.getPublished()),
                        Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .ne(query.getStatus() == null && Boolean.FALSE.equals(query.getPublished()),
                        Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(query.getZone() != null, Article::getZone, query.getZone())
                .eq(query.getCategory() != null, Article::getCategory, query.getCategory())
                .like(query.getKeyword() != null && !query.getKeyword().isBlank(),
                        Article::getTitle, query.getKeyword());
        // 步骤2：分页查询：排序走 PageQuery sortBy 白名单（作者侧登录端点，开放白名单内自定义排序）
        Page<Article> page = articleMapper.selectPage(query.toMpPage(), wrapper);
        // 步骤3：分页信息 + 记录转 VO 一行收敛
        return PageResult.of(page, this::toCardVO);
    }

    /**
     * 编辑文章：归属（本人或 ADMIN）→ 方案B（已发布的文章传正文域字段即拒，防借编辑绕过复审）→
     * 分区权限校验 → 非 null 字段清洗后增量更新 → 转私密在事务提交后发 content.unpublished
     *
     * 已发布的文章（status=2）禁止修改正文内容，只能改可见性/分区等元数据；
     * 未发布的文章允许正常编辑，所有传入字段经 sanitize 后增量更新。
     *
     * 编辑=元数据随便改（任意状态），正文域（标题/正文/配图）只在草稿/驳回态可改，已发布改正文的唯一出路是软删重发。
     *
     * @param id         文章ID
     * @param dto        编辑入参（非 null 字段生效；配图 images 非 null 整体重挂）
     * @param operatorId 操作人ID（归属/权限校验基准）
     * @throws BizException ARTICLE_NOT_FOUND 不存在 / NO_PERMISSION 越权 / ARTICLE_PARAM_INVALID 方案B 拒改或密码缺失
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ArticleUpdateDTO dto, Long operatorId) {

        /**
         * 文章编辑流程：
         *
         * ① 归属与权限检查
         *    只有作者本人或 ADMIN 可以编辑，其他人直接拒绝（防横向越权）。
         *
         * ② 已发布正文域禁改（方案B）
         *    已发布的文章传标题/正文/配图即拒绝——改正文必须软删重发，
         *    防止借“编辑”绕过机审复审；可见性/分区等元数据不受限。
         *
         * ③ 分区变更权限
         *    换分区时按新分区门槛重新校验（与发布同款规则）。
         *
         * ④ 增量清洗更新
         *    非 null 字段才生效；标题/摘要剥标签，正文白名单净化后更新。
         *
         * ⑤ 封面变更处理
         *    新封面先归属校验再绑定引用；旧封面在事务提交后解绑。
         *
         * ⑥ 配图整体重挂
         *    用前端传来的列表完全替换 DB 里的图片：删旧行 → 挂新行 → 绑定新引用，
         *    差集（旧图−新图）在事务提交后逐个解绑。
         *
         * ⑦ 可见性变更联动
         *    转公开：CAS 改状态，事务提交后通知搜索/AI 重新收录（不重审）；
         *    转私密：先失效审核缓存，事务提交后发下架事件（防隐私残留）。
         */

        Article article = requireArticle(id);
        // 归属校验：非本人且非 ADMIN 拒绝（横向越权防护）
        boolean owner = article.getAuthorId().equals(operatorId);
        if (!owner && !SaRoleHelper.isAdmin()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        boolean published = article.getStatus() == ArticleStatusEnum.PUBLISHED;
        // 方案B：status=2 正文域字段（标题/正文/配图）传入即拒——改正文须软删重发，防借编辑绕过复审
        if (published && (dto.getTitle() != null || dto.getContentHtml() != null || dto.getImages() != null)) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 分区变更沿用发布同款权限（ZoneRouter 单点收口 [3.3]）
        if (dto.getZone() != null) {
            zoneRouter.checkPublishPermission(dto.getZone());
        }
        Article upd = new Article().setId(id);
        if (dto.getTitle() != null) {
            upd.setTitle(ArticleHtmlSanitizer.stripTags(dto.getTitle()));
        }
        if (dto.getSummary() != null) {
            upd.setSummary(ArticleHtmlSanitizer.stripTags(dto.getSummary()));
        }
        if (dto.getContentHtml() != null) {
            upd.setContentHtml(ArticleHtmlSanitizer.sanitize(dto.getContentHtml()));
        }
        upd.setCoverFileId(dto.getCoverFileId());
        // 阶段8② 封面引用闭环（编辑链路）：非空且变更才处理（null=不修改封面，MP not_null 更新策略沿用）
        if (dto.getCoverFileId() != null && !dto.getCoverFileId().equals(article.getCoverFileId())) {
            // 归属校验（防挂他人文件当封面）+ 同一事务内登记新封面引用（有问题立刻报错）
            articleImageRefSupport.checkCoverOwnership(dto.getCoverFileId(), article.getAuthorId());
            //事务内单文件登记引用
            articleImageRefSupport.bindSingleRef(dto.getCoverFileId(),
                    ContentBizConstant.FILE_BIZ_TYPE_COVER, id, article.getAuthorId());
            // 事务提交后按 业务类型+文章ID+用户 精确匹配解绑旧封面引用（失败只记 warn 日志，留待对账兑底）
            if (article.getCoverFileId() != null) {
                articleImageRefSupport.unbindRefsAfterCommit(
                        List.of(article.getCoverFileId()),
                        ContentBizConstant.FILE_BIZ_TYPE_COVER, id, article.getAuthorId());
            }
        }
        upd.setCategory(dto.getCategory());
        upd.setZone(dto.getZone());
        boolean toPrivate = false;
        boolean toPublic = false;
        if (dto.getVisibility() != null) {
            // 转公开（visibility 2/3→1 且 status=2，阶段9：原 4 已下线）不需要重审（[4.1]：方案B 禁改正文 + 内容指纹没变，
            // 原审核结论仍然有效）；单独用条件更新改状态，不放进上面的通用更新集合（status 保持不变，不与下架终态流转混淆）
            toPublic = published && dto.getVisibility() == VisibilityEnum.PUBLIC
                    && article.getVisibility() != VisibilityEnum.PUBLIC;
            if (!toPublic) {
                upd.setVisibility(dto.getVisibility());
                // 转非公开（已发布文 → PRIVATE）隐私红线：必发 content.unpublished
                toPrivate = published && dto.getVisibility() == VisibilityEnum.PRIVATE
                        && article.getVisibility() != VisibilityEnum.PRIVATE;
            }
        }
        // 配图整体重挂（仅 status=0/3；null=不修改）
            ///让 DB 里的图片列表 = 前端传来的列表。
        //删除原图片
        if (dto.getImages() != null) {
            checkImageOwnership(dto.getImages(), article.getAuthorId());
            // 阶段6：删前先查旧挂图 fileId（差集解绑基准，删后即无从比对）
            List<String> oldFileIds = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                            .eq(ArticleImage::getArticleId, id))
                    .stream().map(ArticleImage::getFileId).collect(Collectors.toList());
            articleImageMapper.delete(new LambdaQueryWrapper<ArticleImage>()
                    .eq(ArticleImage::getArticleId, id));

            // 重挂新图 + 同一事务内逐张登记引用（有问题立刻报错，不会出现"图挂上了但引用没登记"的中间状态）
            saveArticleImages(dto.getImages(), id, null, article.getAuthorId());

            // 事务提交后把"旧列表减新列表"得到的弃用图逐个解绑（按 业务类型+文章ID+用户 精确匹配；
            // 失败只记 warn 日志不中断：残留引用最多晚删，保守方向不会误删别人的引用）
            List<String> removedFileIds = articleImageRefSupport.diffOldMinusNew(
                    oldFileIds, dto.getImages().stream().map(ArticleImageDTO::getFileId).toList());
            articleImageRefSupport.unbindRefsAfterCommit(removedFileIds,
                    ContentBizConstant.FILE_BIZ_TYPE_ARTICLE_IMG, id, article.getAuthorId());
        }
        //重新更新图片
        articleMapper.updateById(upd);
        if (toPublic) {
            // 转公开带条件更新（要求当前仍是已发布状态）：并发下架/删除时改不动，直接报错
            int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, id)
                    .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                    .set(Article::getVisibility, VisibilityEnum.PUBLIC));
            if (rows == 0) {
                throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
            }
            // afterCommit 发 content.published（search/ai 重新收录；不重建/不重写任何审核缓存，原键仍有效）
            publishPublishedAfterCommit(article);
        }
        if (toPrivate) {
            // 下架审核缓存失效（[3.4] 方案 A，事务内同步执行，fail-safe 不阻断主流程）
            unpublishedCacheCleaner.invalidateArticle(article.getId(), article.getContentHash());
            // afterCommit 发下架事件（search/ai 移除索引与向量，防隐私残留）；
            // 转公开不重审（content_hash 校验）随板块四 [4.2] 补 published 事件
            publishUnpublishedAfterCommit(article, ContentBizConstant.UNPUBLISH_REASON_TO_PRIVATE);
        }
    }

    /**
     * 驳回修改重提：仅作者本人 → 状态机白名单预校验（禁 2→1）→ 带"当前必须是驳回态"的条件改为待审（并发兑底）
     *即驳回并且修改后，再次送审
     * @param articleId  文章ID
     * @param operatorId 操作人ID（须为作者本人）
     * @throws BizException ARTICLE_NOT_FOUND 不存在 / NO_PERMISSION 非本人 / ARTICLE_PARAM_INVALID 状态机拒绝或条件更新没改成
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long articleId, Long operatorId) {

        /**
         * 驳回修改重提流程：
         *
         * ① 本人校验
         *    仅作者本人可重提，他人直接拒绝。
         *
         * ② 状态机预校验
         *    只有“驳回(3)→待审(1)”在白名单内；已发布(2)的文章不能走这条路（改判只认申诉来源）。
         *
         * ③ CAS 流转
         *    where status=3 抢占式改为“待审核”，并发冲突时直接失败（防重复提交）。
         *
         * ④ 送审通知
         *    事务提交后发 content.submitted，重新进入机审队列。
         */

        Article article = requireArticle(articleId);
        // 驳回修改重提仅限作者本人
        if (!article.getAuthorId().equals(operatorId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // 状态机白名单预校验（3→1 允许；2→1 天然拒绝——改判只认申诉来源）
        if (!articleStateMachine.canTransit(article.getStatus(), ArticleStatusEnum.PENDING_REVIEW)) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // CAS 3→1（并发兜底：where status=3，MP 自动拼 del_flag=0）
        int rows = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getStatus, ArticleStatusEnum.REJECTED)
                .set(Article::getStatus, ArticleStatusEnum.PENDING_REVIEW));
        if (rows == 0) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 驳回重提照常送审：afterCommit 发单条 content.submitted（事件体不含正文，review 回查）
        registerSubmitEventAfterCommit(articleId, article.getContentHash());
    }

    /**
     * 软删文章：作者本人或 ADMIN → CAS 软删（del_flag 逻辑删除）→ 操作日志剥控制字符防注入 →
     * afterCommit 必发 content.unpublished（隐私红线：search/ai 移除残留）
     *
     * @param articleId  文章ID
     * @param operatorId 操作人ID
     * @throws BizException ARTICLE_NOT_FOUND 不存在或已删（CAS affected=0） / NO_PERMISSION 越权
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long articleId, Long operatorId) {

        /**
         * 软删流程：
         *
         * ① 归属校验
         *    作者本人或 ADMIN 可删，其他人拒绝。
         *
         * ② 逻辑删除
         *    MP 自动把 DELETE 转 UPDATE del_flag=1；删不到说明文章不存在，直接报错。
         *
         * ③ 引用解绑
         *    查全量配图+封面，事务提交后逐个解绑文件引用（防残留引用锁死秒传共享文件）。
         *
         * ④ 审核缓存失效
         *    精确删 last-hash + 通配清审核结果缓存（防脏免审判定）。
         *
         * ⑤ 操作日志
         *    标题剥控制字符防日志伪造注入。
         *
         * ⑥ 下架事件
         *    事务提交后必发 content.unpublished（隐私红线：搜索索引/AI 向量库同步移除残留）。
         */

        Article article = requireArticle(articleId);
        // 作者本人或 ADMIN 可删
        if (!article.getAuthorId().equals(operatorId) && !SaRoleHelper.isAdmin()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        // CAS 软删（MP 逻辑删除：DELETE 转 UPDATE del_flag=1，自动拼 del_flag=0）
        int rows = articleMapper.deleteById(articleId);
        if (rows == 0) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // 阶段6 软删红线：查全量配图（软删前基准），afterCommit 逐图 unbind ARTICLE_IMG 引用
        // （补偿性清理 warn 留痕不阻断：残留引用仅晚删/不删，防锁死秒传共享文件被他人复用）
        //mysql查询配图列表
        List<String> deletedFileIds = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                        .eq(ArticleImage::getArticleId, articleId))
                .stream().map(ArticleImage::getFileId).collect(Collectors.toList());
        //删除配图引用
        articleImageRefSupport.unbindRefsAfterCommit(deletedFileIds,
                ContentBizConstant.FILE_BIZ_TYPE_ARTICLE_IMG, articleId, article.getAuthorId());
        // 阶段8② 软删封面解绑：coverFileId 游离在 article_image 外（无图行可查），按主表字段直取 unbind
        if (article.getCoverFileId() != null) {
            articleImageRefSupport.unbindRefsAfterCommit(
                    List.of(article.getCoverFileId()),
                    ContentBizConstant.FILE_BIZ_TYPE_COVER, articleId, article.getAuthorId());
        }
        // 下架审核缓存失效（[3.4] 方案 A：last-hash 精确删 + text/img-result SCAN，fail-safe 不阻断主流程，
        // 残留键 TTL 兜底；原板块八 TODO 提前在本板块收口）
        unpublishedCacheCleaner.invalidateArticle(articleId, article.getContentHash());
        // 作者侧操作日志：标题为 UGC 自由文本，剥 \r\n 防日志伪造注入（对齐 review §七.1 落日志口径）
        log.info("文章软删，articleId={}，operatorId={}，title={}",
                articleId, operatorId, LogSanitizeUtil.sanitizeForLog(article.getTitle()));
        // 发布下架通知：软删隐私红线：afterCommit 必发 content.unpublished（search/ai 移除残留）
        publishUnpublishedAfterCommit(article, ContentBizConstant.UNPUBLISH_REASON_DELETE);
    }

    /**
     * 文章详情（visibility 三级判定链，§6.4；阶段9：密码访问已下线）：非本人读非发布态按不存在处理不暴露存在性；
     * PRIVATE 非本人拒 / FOLLOWERS 降级宁严勿松；配图占位符渲染回显
     *
     * @param articleId 文章ID
     * @param viewerId  查看者ID（null=匿名，仅可读发布态）
     * @return 详情 VO（正文按可见性判定放行）
     * @throws BizException ARTICLE_NOT_FOUND 不存在/非发布态/可见性拦截
     */
    @Override
    public ArticleDetailVO getDetail(Long articleId, Long viewerId) {

        /**
         * 详情查询流程（可见性判定链）：
         *
         * ① 查文章
         *    已删/不存在直接按“文章不存在”处理。
         *
         * ② 非发布态拦截
         *    非本人看未发布/驳回/下架的文章，一律按不存在处理，不暴露“这篇文章存在”的事实。
         *
         * ③ 可见性判定（仅非本人时）
         *    - 匿名用户：非公开一律按不存在（省一次关注关系查询）；
         *    - 仅自己(PRIVATE)：非本人按不存在；
         *    - 仅关注者(FOLLOWERS)：回查关注关系（短 TTL 缓存），非关注者按不存在；
         *    - 公开(PUBLIC)：直接放行。
         *
         * ④ 渲染回显
         *    查配图列表 → 批量换 URL（防 N+1）→ 正文占位符替换成带对齐样式的 <img>；
         *    数据不全的占位符原样保留降级，不破坏正文结构。
         */

        Article article = requireArticle(articleId);
        boolean owner = viewerId != null && article.getAuthorId().equals(viewerId);
        // 非本人读非发布态 → 按不存在处理（不暴露草稿/驳回/下架存在性）
        if (!owner && article.getStatus() != ArticleStatusEnum.PUBLISHED) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        VisibilityEnum visibility = article.getVisibility();
        String contentHtml = article.getContentHtml();
        if (!owner) {
            // 匿名（viewerId=null）非公开一律按不存在处理：不回查关注关系（省一次跨服务调用），
            // 也避免 ownerId:null 负缓存键污染
            if (viewerId == null && visibility != VisibilityEnum.PUBLIC) {
                throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
            }
            switch (visibility) {
                case PRIVATE -> {
                    // 仅自己：非本人按不存在，不暴露存在性
                    throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
                }
                case FOLLOWERS -> {
                    // 仅关注者（[4.1]）：回查 social 关注关系（短 TTL 缓存 ±10% 抖动，查不到不放行）；
                    // 非关注者按不存在处理，不暴露存在性
                    if (!followRelationCache.isFollowing(article.getAuthorId(), viewerId)) {
                        throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
                    }
                }
                default -> {
                    // PUBLIC：放行
                }
            }
        }
        // 渲染：查配图 → 批量换 URL → 占位符替换为带对齐 class 的 <img>
        List<ArticleImage> images = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getArticleId, articleId)
                .orderByAsc(ArticleImage::getPositionOrder));
        if (contentHtml != null && !contentHtml.isEmpty() && !images.isEmpty()) {
            contentHtml = articleImageRenderSupport.renderImages(contentHtml, images);
        }
        // 收藏状态回填（阶段16）：匿名 false；登录用户 uk 等值查询（详情单条查询，无 N+1 问题）
        boolean favorited = viewerId != null && favoriteStateSupport.isFavorited(
                viewerId, FavoriteTargetTypeEnum.ARTICLE.getCode(), articleId);
        return new ArticleDetailVO()
                .setId(article.getId())
                .setAuthorId(article.getAuthorId())
                .setTitle(article.getTitle())
                .setSummary(article.getSummary())
                .setContentHtml(contentHtml)
                .setCoverFileId(article.getCoverFileId())
                .setCategory(article.getCategory())
                .setZone(article.getZone())
                .setStatus(owner ? article.getStatus() : ArticleStatusEnum.PUBLISHED)
                .setVisibility(visibility)
                .setViewCnt(article.getViewCnt())
                .setLikeCnt(article.getLikeCnt())
                .setCommentCnt(article.getCommentCnt())
                .setTagNames(Collections.emptyList())
                .setImages(images.stream().map(this::toImageVO).collect(Collectors.toList()))
                .setCreateTime(article.getCreateTime())
                .setUpdateTime(article.getUpdateTime())
                .setFavorited(favorited);
    }

    /**
     * 公开文章列表（匿名可读规则：status=2 且 visibility=1；select 排除 content_html 大字段延迟加载）
     *
     * @param query 分页查询条件（zone/category 可空=全部；分页参数经 PageQuery 统一兜底钳制）
     * @return 分页结果（total/totalPages/hasNext 供前端分页组件渲染；卡片 publishTime 取 update_time）
     */
    @Override
    public PageResult<ArticleCardVO> listPublic(ArticlePublicQueryDTO query) {

        /**
         * 公开列表查询流程：
         *
         * ① 组装查询条件
         *    仅“已发布+公开”的文章匿名可见；分区/分类按需过滤；
         *    排除正文大字段（列表不加载 content_html，详情页才取，减少传输量）。
         *    分类白名单校验已前移到参数绑定层（ArticlePublicQueryDTO.category 枚举 + 统一转换器，脏值 400）；
         *    前端传的 status/published/keyword 在本端点一律忽略（匿名公开列表不接受状态过滤，实现为白名单式组装：只拼 zone/category）
         *
         * ② MyBatis-Plus 分页查询
         *    页码/页大小兜底与上限钳制由 PageQuery 收口；count 由分页插件自动执行；
         *    排序固定 update_time 降序（代码显式 OrderItem 优先级最高，不开放匿名前端自定义 sortBy）。
         *
         * ③ 封装 PageResult 返回（含 total/totalPages/hasNext）。
         */

        // 公开读仅 status=2 且 visibility=1（匿名可见）；列表查询不加载 content_html 大字段（详情页才取）
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .select(Article.class, i -> !Article.COLUMN_CONTENT_HTML.equals(i.getColumn()))
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                .eq(query.getZone() != null, Article::getZone, query.getZone())
                .eq(query.getCategory() != null, Article::getCategory, query.getCategory());
        // 分页兑底（页码≥1/页大小上限钳制）与排序由 PageQuery.toMpPage 收口；显式传 update_time 降序覆盖前端 sortBy
        Page<Article> page = articleMapper.selectPage(query.toMpPage(OrderItem.desc(PageConstant.UPDATE_TIME)), wrapper);
        // 分页信息 + 记录转 VO 一行收敛（PageResult.of(IPage, Function) 工厂）
        return PageResult.of(page, this::toCardVO);
    }

    /**
     * 热门文章列表（view_cnt 倒序直查，公开读同一规则；缓存三防随板块五接入 Redis）
     *
     * @param pageSize 取前 N 条
     * @return 文章卡片列表
     */
    @Override
    public List<ArticleCardVO> listHot(int pageSize) {

        /**
         * 热门列表查询流程：
         *
         * ① 组装查询条件
         *    仅“已发布+公开”的文章；排除正文大字段延迟加载。
         *
         * ② 排序返回
         *    按浏览量倒序取前 N 条，转卡片 VO 列表。
         */

        // 热门：view_cnt 倒序直查；缓存三防（互斥重建/空值短缓存/TTL±10%抖动）随板块五 feed 接入 Redis
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .select(Article.class, i -> !Article.COLUMN_CONTENT_HTML.equals(i.getColumn()))
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                .orderByDesc(Article::getViewCnt);
        // TopN 无分页语义：取第一页即可，count 关闭省一次全表计数；pageSize 钳制到全局上限（防超大 N 拖垮数据库）
        return articleMapper
                .selectList(Page.of(1, Math.min(pageSize, PageConstant.MAX_PAGE_SIZE), false), wrapper)
                .stream().map(this::toCardVO).collect(Collectors.toList());
    }

    // ==================== 私有辅助 ====================

    /**
     * 查文章并判空（del_flag 逻辑删除由 MP 自动过滤，已删即查不到 → 不存在）
     *
     * @param articleId 文章ID
     * @return 文章实体（查无即抛 ARTICLE_NOT_FOUND）
     */
    private Article requireArticle(Long articleId) {
        Article article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        return article;
    }

    /**
     * 插图归属校验（阶段6 修 N+1 问题）：委托 ArticleImageRefSupport 批量校验
     * （fileId 必须归属本人且是文章配图类型，有问题立刻报错，防引用他人文件/违规图）；
     * 原来逐图远程查询（最多 50 次调用）改为批量查询一次往返
     *
     * @param images   待校验配图列表
     * @param authorId 期望归属用户ID
     */
    private void checkImageOwnership(List<ArticleImageDTO> images, Long authorId) {
        articleImageRefSupport.checkOwnership(images, authorId);
    }

    /**
     * 配图批量落 article_image（article_id/draft_id 二选一）+ 同一事务内逐张登记引用
     * （阶段6 方案C：登记失败立刻报错，随本地事务回滚，不会出现"图挂上了但引用没登记"的中间状态）
     *
     * @param images    配图列表
     * @param articleId 正式文章ID（草稿场景传 null）
     * @param draftId   草稿ID（发布场景传 null）
     * @param ownerId   引用归属用户ID（引用登记四要素：业务类型+业务ID+文件ID+归属用户 之一）
     */
    private void saveArticleImages(List<ArticleImageDTO> images, Long articleId, Long draftId, Long ownerId) {
        for (ArticleImageDTO item : images) {
            articleImageMapper.insert(new ArticleImage()
                    .setArticleId(articleId)
                    .setDraftId(draftId)
                    .setFileId(item.getFileId())
                    .setPositionOrder(item.getPositionOrder())
                    .setAlign(item.getAlign())
                    .setWidth(item.getWidth())
                    .setHeight(item.getHeight()));
        }
        // 同一事务内登记引用（重复登记无副作用）：文章维度 ARTICLE_IMG+文章ID；草稿维度 DRAFT_IMAGE+草稿ID
        if (articleId != null) {
            articleImageRefSupport.bindArticleRefs(images, articleId, ownerId);
        } else {
            articleImageRefSupport.bindDraftRefs(images, draftId, ownerId);
        }
    }

    /**
     * 发布链路归属切换（阶段6 补齐，决策 D2）：dto.draftId 非空时按草稿维度定位旧图行，
     * 删 draft_id 图行（消除双行残留）→ afterCommit unbind DRAFT_IMAGE 旧引用（补偿性清理，warn 留痕）。
     * <p>校验：草稿存在且归属本人（防传他人 draftId 悬空其引用）；草稿图与新文章图差集才解绑，
     * 继续沿用的图引用不重复解绑</p>
     *
     * @param draftId  来源草稿ID（可空）
     * @param images   新文章配图列表（差集计算基准）
     * @param authorId 作者用户ID（草稿归属校验 + unbind ownerId）
     */
    private void switchDraftImageOwnership(Long draftId, List<ArticleImageDTO> images, Long authorId) {
        if (draftId == null) {
            return;
        }
        // 定位该草稿维度图行（发布时草稿图行原样残留即双行病灶，此处收口）
        List<ArticleImage> draftImages = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getDraftId, draftId));
        // 归属校验：草稿必须存在且归属本人（article_draft.author_id 比对，防传他人 draftId 悬空其引用）
        ArticleDraft draft = articleDraftMapper.selectById(draftId);
        if (draft == null || !authorId.equals(draft.getAuthorId())) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 差集解绑：旧草稿图 − 新文章图 = 真正被弃用的图（继续沿用的不解绑，防引用计数误减）
        List<String> newFileIds = images.stream().map(ArticleImageDTO::getFileId).toList();
        List<String> removedFileIds = articleImageRefSupport.diffOldMinusNew(
                draftImages.stream().map(ArticleImage::getFileId).collect(Collectors.toList()), newFileIds);
        // 删 draft_id 图行（物理删，article_image 无 del_flag；文章行已由 saveArticleImages 新插）
        articleImageMapper.delete(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getDraftId, draftId));
        // afterCommit 补偿性解绑 DRAFT_IMAGE 维度旧引用（失败 warn 留痕，对账兑底）
        articleImageRefSupport.unbindRefsAfterCommit(
                removedFileIds, ContentBizConstant.FILE_BIZ_TYPE_DRAFT_IMG, draftId, authorId);
    }

    /**
     * 发布归属切换联动（阶段8②）：草稿封面 → 文章封面的引用交接。
     * <p>dto.draftId 非空且草稿归属本人时，若草稿原封面存在且与新封面不同（差集口径：
不同（沿同一个封面就不解绑，
     * 防引用计数误减），事务提交后解绑草稿维度的旧封面引用
     * （失败只记 warn 日志，残留由对账兑底）</p>
     *
     * @param draftId     来源草稿ID（可空，空则无切换语义直接返回）
     * @param newCoverId  新文章封面 fileId（可空，发布无封面则旧草稿封面整体弃用）
     * @param authorId    作者用户ID（草稿归属校验 + unbind ownerId 四元组之一）
     */
    private void unbindDraftCoverIfReplaced(Long draftId, String newCoverId, Long authorId) {
        if (draftId == null) {
            return;
        }
        // 归属校验：草稿必须存在且归属本人（与 switchDraftImageOwnership 同一规则，防传他人 draftId 悬空其引用）
        ArticleDraft draft = articleDraftMapper.selectById(draftId);
        if (draft == null || !authorId.equals(draft.getAuthorId())) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 只有草稿原封面非空且与新封面不同才解绑（同一个封面继续沿用，两边都没有封面同样不解绑）
        String oldCoverId = draft.getCoverFileId();
        if (oldCoverId != null && !oldCoverId.equals(newCoverId)) {
            articleImageRefSupport.unbindRefsAfterCommit(List.of(oldCoverId),
                    ContentBizConstant.FILE_BIZ_TYPE_COVER, draftId, authorId);
        }
    }

    /**
     * 注册事务提交后发送 content.submitted（事务顺序铁律：本地事务提交 → 事件发送，失败重投/DLQ）；
     * 事件体不含正文，review 消费端按 bizType+bizId+scene 定位任务后经 ContentClient 回查
     *
     * @param articleId   文章ID（事件 bizId）
     * @param contentHash 送审文本指纹（SubmitTextAssembler 同一规则：标题+分隔符+正文 SHA-256）
     */
    private void registerSubmitEventAfterCommit(Long articleId, String contentHash) {
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
     * 计算配图聚合指纹（4-3.4 同一规则，必须与 review 侧复审任务的 content_hash 完全一致，错配事件会被消费端丢弃）：
     * 按发布入参登记顺序取 fileId → FileClient 批量查 sha256（审核链路读失败立刻报错）→
     * sha256 字符串排序拼接 → 再整体做一次 SHA-256（复用 SubmitTextAssembler.contentHash 的二次哈希规则）
     *
     * @param images 发布入参配图列表（fileId 登记顺序）
     * @return 64 位小写十六进制聚合指纹
     * @throws BizException ARTICLE_IMG_REFUSED 指纹缺图（脏数据聚合不完整会架空图片复审，fail-fast 拒绝发布）
     */
    private String aggregatedImgHash(List<ArticleImageDTO> images) {
        List<String> fileIds = images.stream().map(ArticleImageDTO::getFileId).toList();
        Map<String, String> shaMap = fileClient.batchGetSha256(fileIds).getData();
        if (shaMap == null || shaMap.size() < fileIds.stream().distinct().count()) {
            // 指纹缺一不可（缺失说明文件元数据是脏数据或文件已删，聚合结果不完整），有问题立刻报错拒绝发布；
            // 报错携带缺失的 fileId 列表供前端定位（P1 发布异常保稿 2026-10-28）
            List<String> missingFileIds = fileIds.stream()
                    .filter(id -> shaMap == null || !shaMap.containsKey(id))
                    .distinct()
                    .toList();
            throw new BizException(ErrorCode.ARTICLE_IMG_REFUSED,
                    String.format(ContentBizConstant.IMG_HASH_MISSING_MSG, String.join(",", missingFileIds)));
        }
        // sha256 排序拼接后整体再做一次 SHA-256（与 LastHashExemptChecker/review 侧用同一套聚合规则）
        String concatenated = shaMap.values().stream().sorted().collect(Collectors.joining());
        return SubmitTextAssembler.contentHash(concatenated);
    }

    /**
     * 注册事务提交后发送 image.submit（配图发布复审，事务顺序铁律：本地事务提交 → 事件发送，失败重投/DLQ）；
     * bizType=1-文章、bizId=articleId、scene=ARTICLE_IMG_RE_REVIEW、contentHash=配图聚合指纹，
     * review 消费端按 bizType+bizId+scene 定位文章级复审任务后反查配图逐张送审并聚合流转
     *
     * @param articleId         文章ID（事件 bizId）
     * @param aggregatedImgHash 配图聚合指纹（事件 contentHash）
     */
    private void registerImageSubmitEventAfterCommit(Long articleId, String aggregatedImgHash) {
        ImageSubmitEvent event = new ImageSubmitEvent();
        event.setModType(ModerationConstant.MOD_TYPE_AUTO);
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(String.valueOf(articleId));
        event.setScene(ModerationConstant.SCENE_ARTICLE_IMG_RE_REVIEW);
        event.setContentHash(aggregatedImgHash);
        event.setRetryCount(0);
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
    private void publishUnpublishedAfterCommit(Article article, int reason) {
        ContentUnpublishedEvent event = new ContentUnpublishedEvent();
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(article.getId());
        event.setContentHash(article.getContentHash());
        event.setReason(reason);
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
    private void publishPublishedAfterCommit(Article article) {
        ContentPublishedEvent event = new ContentPublishedEvent();
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(article.getId());
        event.setContentHash(article.getContentHash());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contentEventProducer.sendPublished(event);
            }
        });
    }


    /**
     * 配图实体 → VO（详情回显）
     *
     * @param image 配图实体
     * @return 配图 VO
     */
    private ArticleImageVO toImageVO(ArticleImage image) {
        return new ArticleImageVO()
                .setFileId(image.getFileId())
                .setPositionOrder(image.getPositionOrder())
                .setAlign(image.getAlign())
                .setWidth(image.getWidth())
                .setHeight(image.getHeight());
    }

    /**
     * 文章实体 → 卡片 VO（publishTime 取 update_time，即文章发布那一刻的时间）
     *
     * @param article 文章实体（不含 content_html 大字段）
     * @return 卡片 VO
     */
    private ArticleCardVO toCardVO(Article article) {
        return new ArticleCardVO()
                .setId(article.getId())
                .setTitle(article.getTitle())
                .setSummary(article.getSummary())
                .setCoverFileId(article.getCoverFileId())
                .setCategory(article.getCategory())
                .setZone(article.getZone())
                .setAuthorId(article.getAuthorId())
                .setViewCnt(article.getViewCnt())
                .setLikeCnt(article.getLikeCnt())
                .setCommentCnt(article.getCommentCnt())
                .setPublishTime(article.getUpdateTime());
    }
}