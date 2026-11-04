package com.litisle.litislecontent.service.support;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaHitResult;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litisleapi.moderation.DfaPublishGate;
import com.litisle.litisleapi.moderation.LastHashExemptChecker;
import com.litisle.litisleapi.moderation.LastHashStore;
import com.litisle.litisleapi.moderation.PublishTextPrecheck;
import com.litisle.litisleapi.moderation.SubmitTextAssembler;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.dto.ArticleImageDTO;
import com.litisle.litislecontent.domain.dto.ArticlePublishDTO;
import com.litisle.litislecontent.domain.dto.ArticleSaveDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleDraft;
import com.litisle.litislecontent.domain.router.ZoneRouter;
import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import com.litisle.litislecontent.mapper.ArticleDraftMapper;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.service.support.ArticleImageRefSupport;
import com.litisle.litislecontent.service.support.ArticleImageSupport;
import com.litisle.litislecontent.service.support.ArticleEventSupport;
import com.litisle.litislecontent.service.support.PublishDraftSnapshotSupport;
import com.litisle.litislecontent.utils.ArticleHtmlSanitizer;
import com.litisle.litislecontent.utils.ContentLengthGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文章发布写链路域支撑组件（原 ArticleServiceImpl 发布/建档私有链路收口）。
 *
 * 作用：
 * 承接 createAndPublish（§2.4 发布编排）与 saveDraftRow（建档不发布）两条写链路的完整方法体，
 * 以及发布/建档/编辑三方共用的摘要敏感词同步扫描：
 *   ① createAndPublish：分区权限 → 图文校验 → 清洗 → 敏感词扫描 → 指纹落库（status=1 待审）
 *      → 配图/封面接线 → A2 免审判定三分支 → A3 事务提交后发 content.submitted；
 *   ② saveDraftRow：与发布同加工链路，差异仅在落库 status=0 草稿态行、不发任何 MQ 事件；
 *   ③ checkSummarySensitiveWords：stripTags 后摘要独立过 DFA（黑名单拦/观察词留痕，Q1）。
 *
 * 【事务边界】发布/建档方法保留 @Transactional（门面委托调用经 Spring 代理生效，语义与拆分前一致）
 * 【跨域协作】配图归属/挂载/聚合指纹委托 ArticleImageSupport；事务后事件委托 ArticleEventSupport；
 * file_ref 引用登记/封面归属校验委托 ArticleImageRefSupport
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticlePublishSupport {

    /** 文章 Mapper（文章落库/免审 CAS 状态流转数据源） */
    private final ArticleMapper articleMapper;

    /** 草稿快照 Mapper（P1 发布回填：快照草稿行 article_id 关联回写数据源） */
    private final ArticleDraftMapper articleDraftMapper;

    /** 分区路由权限校验（[3.3] 单点收口：发布/建档分区权限统一走本组件） */
    private final ZoneRouter zoneRouter;

    /** DFA 同步闸门门面（宿主本地 Bean，发布链路零网络） */
    private final DfaFacade dfaFacade;

    /** last-hash Redis 读写适配（契约层 RedisAdapter 宿主注入，免审判定用） */
    private final LastHashStore.RedisAdapter lastHashRedisAdapter;

    /** 发布入口草稿快照组件（P1 发布异常保稿：校验前 UPSERT 快照进 article_draft，REQUIRES_NEW 独立事务） */
    private final PublishDraftSnapshotSupport publishDraftSnapshotSupport;

    /** 文章/草稿插图引用支撑组件（封面归属校验 + 封面 file_ref 事务内登记） */
    private final ArticleImageRefSupport articleImageRefSupport;

    /** 文章配图归属域支撑组件（插图归属校验/配图挂载/草稿归属切换/配图聚合指纹） */
    private final ArticleImageSupport articleImageSupport;

    /** 文章事务后事件域支撑组件（事务提交后发 submitted/published/image.submit 事件） */
    private final ArticleEventSupport articleEventSupport;

    /** 摘要 DFA 扫描场景码（Q1 场景码外置：当前与正文共用 ARTICLE 词库；未来升级 2b 独立送审单元时仅调整本常量，调用方零改动） */
    private static final String SUMMARY_DFA_SCENE = DfaScenes.ARTICLE;

    /**
     * 摘要敏感词同步扫描（Q1：摘要此前「从不审核+已发布可随意改」，补 DFA 同步闸门实现零暴露窗口）
     *
     * 作用：
     * 对 stripTags 后的纯文本摘要独立跑 DFA 检查：命中黑名单词（HIGH）抛 70001 拦截，
     * 命中观察词（MID）仅记日志留痕不拦截（与正文闸门同口径）。
     * 摘要不进 SubmitTextAssembler.assemble 口径、不参与 content_hash——
     * 存量文章免审缓存不受影响（摘要变更不会导致免审缓存失配）。
     *
     * @param summary  stripTags 后的摘要纯文本（null/空白直接跳过）
     * @param authorId 作者用户ID（观察词留痕日志用）
     * @throws BizException SENSITIVE_WORD_DETECTED 摘要命中黑名单词
     */
    public void checkSummarySensitiveWords(String summary, Long authorId) {
        // 空/空白摘要无扫描意义，直接放行
        if (summary == null || summary.isBlank()) {
            return;
        }
        // 摘要独立扫描（场景码外置）：与正文闸门共用 DfaPublishGate 裁决口径（HIGH 拦/MID 标记）
        DfaHitResult hitResult = dfaFacade.check(SUMMARY_DFA_SCENE, summary);
        DfaPublishGate.DfaGateDecision gate = DfaPublishGate.decide(SUMMARY_DFA_SCENE, hitResult);
        if (gate.blocked()) {
            // 黑名单词同步拦截：改摘要唯一出路口径=同步扫描通过即可改（无需软删重发）
            throw new BizException(ErrorCode.SENSITIVE_WORD_DETECTED);
        }
        if (gate.midMark()) {
            // 观察词仅留痕：送审后最终处理由 review 审核端决定
            log.info("摘要 DFA MID 命中仅标记不拦截：authorId={}，wordlibVer={}", authorId, hitResult.wordlibVer());
        }
    }

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
        // 正文长度硬上限（防滥用兜底）：content_html 为 LONGTEXT 无 DB 约束，超大正文会打爆送审回查/
        // search 索引/ai RAG 摄入；不进 hasText 分支（纯图文章的 contentHtml 残片同样受控）；
        // 超长篇创作的正确载体是书籍分章（每章独立过机审），此处仅拦截并软引导，不做自动拆分
        ContentLengthGuard.checkContentLength(dto.getContentHtml());

        // 插图归属校验：fileId 归属本人且 bizType=ARTICLE_IMG（防引用他人文件/违规图）
        if (hasImg) {
            articleImageSupport.checkImageOwnership(dto.getImages(), authorId);
        }
        // 阶段8② 封面归属校验补口：coverFileId 可挂他人文件当封面的漏洞收口（阶段6 只查正文 images）
        articleImageRefSupport.checkCoverOwnership(dto.getCoverFileId(), authorId);

        // 内容清洗：标题/摘要剥掉所有标签变纯文本；正文按白名单过滤危险标签后入库（图片占位符原样保留）
        String title = ArticleHtmlSanitizer.stripTags(dto.getTitle());
        String summary = ArticleHtmlSanitizer.stripTags(dto.getSummary());
        String contentHtml = hasText ? ArticleHtmlSanitizer.sanitize(dto.getContentHtml()) : dto.getContentHtml();

        // Q1 摘要同步闸门：stripTags 后纯文本摘要独立过 DFA（黑名单拦截/观察词留痕）；
        // 不受 hasText 限制（纯图文章同样有摘要）；不进 assemble 口径、不影响 content_hash
        checkSummarySensitiveWords(summary, authorId);

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
            articleImageSupport.switchDraftImageOwnership(dto.getDraftId(), dto.getImages(), authorId);
        }
        // 配图落 article_image（发布即挂到文章ID下）+ 同一事务内逐张登记文件引用（有问题立刻报错）
        if (hasImg) {
            articleImageSupport.saveArticleImages(dto.getImages(), article.getId(), null, authorId);
        }
        // 阶段8② 封面引用登记：同一事务内把封面登记到文章ID下（有问题立刻报错，与正文配图同一链路）
        articleImageRefSupport.bindSingleRef(dto.getCoverFileId(),
                ContentBizConstant.FILE_BIZ_TYPE_COVER, article.getId(), authorId);
        // 发布归属切换联动（阶段8②）：dto.draftId 非空且草稿封面与新封面不同时，
        // 事务提交后解绑草稿维度的旧封面引用（沿用同一个封面就不解绑）
        //解绑草稿的封面引用
        articleImageSupport.unbindDraftCoverIfReplaced(dto.getDraftId(), dto.getCoverFileId(), authorId);
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
        String aggregatedImgHash = hasImg ? articleImageSupport.aggregatedImgHash(dto.getImages()) : null;

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
            articleEventSupport.publishPublishedAfterCommit(article);
            return article.getId();
        }

        // 步骤A3：照常送审——事务提交后发单条 content.submitted（事件体不含正文，review 端回查数据库拿内容）
        articleEventSupport.registerSubmitEventAfterCommit(article.getId(), article.getContentHash());
        // 含图文章发布复审（免文不免图）：afterCommit 发 image.submit（scene=ARTICLE_IMG_RE_REVIEW，
        // contentHash=配图聚合指纹；review 消费端校验指纹一致性，错配事件直接丢弃）
        if (hasImg) {
            articleEventSupport.registerImageSubmitEventAfterCommit(article.getId(), aggregatedImgHash);
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
        // 正文长度硬上限（防滥用兜底，与发布同上限）：防绕过发布口从建档路径灌入超大正文行
        ContentLengthGuard.checkContentLength(dto.getContentHtml());

        // 步骤③：归属校验（配图逐张 + 封面，防引用他人文件）
        if (hasImg) {
            articleImageSupport.checkImageOwnership(dto.getImages(), authorId);
        }
        articleImageRefSupport.checkCoverOwnership(dto.getCoverFileId(), authorId);

        // 步骤④：内容净化（与发布同一套规则，保证建档与发布的 contentHash 同源）
        String title = ArticleHtmlSanitizer.stripTags(dto.getTitle());
        String summary = ArticleHtmlSanitizer.stripTags(dto.getSummary());
        String contentHtml = hasText ? ArticleHtmlSanitizer.sanitize(dto.getContentHtml()) : dto.getContentHtml();

        // 步骤④+（Q1）：摘要过 DFA 同步闸门（黑名单建档即拦/观察词留痕——与发布摘要闸门同口径）
        checkSummarySensitiveWords(summary, authorId);

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
            articleImageSupport.switchDraftImageOwnership(dto.getDraftId(), dto.getImages(), authorId);
            // 配图落 article_image + 同一事务内逐张登记文件引用（有问题立刻报错）
            articleImageSupport.saveArticleImages(dto.getImages(), article.getId(), null, authorId);
        }
        // 封面引用登记（阶段8②）：同一事务内把封面登记到文章ID下
        articleImageRefSupport.bindSingleRef(dto.getCoverFileId(),
                ContentBizConstant.FILE_BIZ_TYPE_COVER, article.getId(), authorId);
        // 换了封面才解绑草稿维度的旧封面引用（事务提交后执行，沿用同一个封面就不解绑）
        articleImageSupport.unbindDraftCoverIfReplaced(dto.getDraftId(), dto.getCoverFileId(), authorId);

        // 步骤⑧：不发任何事件（草稿态不对外；送审事件由 publishBatch 的 doPublishSingle 在 0→1 后发送）
        return article.getId();
    }
}
