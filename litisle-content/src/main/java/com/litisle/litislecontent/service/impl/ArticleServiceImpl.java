package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.crypto.PasswordCrypto;
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
import com.litisle.litislecontent.cache.FollowRelationCache;
import com.litisle.litislecontent.cache.UnpublishedCacheCleaner;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.dto.ArticleImageDTO;
import com.litisle.litislecontent.domain.dto.ArticlePublishDTO;
import com.litisle.litislecontent.domain.dto.ArticleUpdateDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.domain.router.ZoneRouter;
import com.litisle.litislecontent.domain.statemachine.ArticleStateMachine;
import com.litisle.litislecontent.domain.vo.ArticleCardVO;
import com.litisle.litislecontent.domain.vo.ArticleDetailVO;
import com.litisle.litislecontent.domain.vo.ArticleImageVO;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import com.litisle.litislecontent.mapper.ArticleMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.litisle.litislecontent.mq.ContentEventProducer;
import com.litisle.litislecontent.service.ArticleService;
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
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * 文章域实现（CRUD/详情可见性判定链/软删/公开读）。
 *
 * 【可见性判定链（§6.4）】getDetail 按 visibility 收口：
 *   1-公开放行；2-仅自己（owner 校验，非本人按不存在处理，不暴露存在性）；
 *   3-仅关注者（回查 social 关注关系，降级=按非关注者处理——宁严勿松，结果加短 TTL 缓存±10%抖动）；
 *   4-密码访问（元信息可见，正文需先过 verify-password）
 * 【软删红线】deleteById 必发 content.unpublished（四路径统一收口之一，防向量库/索引残留）
 * 【大字段延迟加载】列表查询不带 content_html，详情页单独取
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private final ArticleMapper articleMapper;

    private final ArticleImageMapper articleImageMapper;

    private final FileClient fileClient;

    private final ContentEventProducer contentEventProducer;

    private final ArticleStateMachine articleStateMachine;

    private final PasswordCrypto passwordCrypto;

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

    /** 字符串 Redis 模板（[4.2] 密码验证通过凭证标记读取） */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 创建并发布（同步段，§2.4 发布编排）：A0 分区权限 → A1 图文联合判定+契约门面校验 → sanitize 三层铁律 →
     * DFA 同步闸门（HIGH 拦 70001 / MID 留痕）→ contentHash 落库（status=1 待审）→ 配图挂 article_id →
     * A2 last-hash 免审三分支（EXEMPT_PASS 直落 2 / REJECT_HINT 前置提示 / SUBMIT）→ A3 afterCommit 发 content.submitted
     *
     * @param dto      发布入参（标题/正文/配图/分区/可见性等）
     * @param authorId 作者用户ID（插图归属校验基准）
     * @return 新建文章ID
     * @throws BizException CONTENT_EMPTY 图文同空 / ARTICLE_PARAM_INVALID 参数非法 / ARTICLE_IMG_REFUSED 插图越权
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createAndPublish(ArticlePublishDTO dto, Long authorId) {
        // 步骤A0：分区权限（ZoneRouter 单点收口 [3.3]：zone=2 需 AUTHOR；zone=3 仅 ADMIN/运营；默认普通分区）
        ZoneEnum zone = dto.getZone() == null ? ZoneEnum.NORMAL : dto.getZone();
        zoneRouter.checkPublishPermission(zone);
        // 步骤A1：图文联合判定——正文无可见文本且无配图同空拒绝（图文分支：有图时文本链路 trivially PASS）
        boolean hasText = PublishTextPrecheck.hasVisibleText(dto.getContentHtml());
        boolean hasImg = dto.getImages() != null && !dto.getImages().isEmpty();
        if (!hasText && !hasImg) {
            throw new BizException(ErrorCode.CONTENT_EMPTY);
        }
        if (hasText) {
            // 契约门面前置校验（标题 trim 非空/≤100 + 去标签可见文本非空；先于 contentHash 计算与落库）
            PublishTextPrecheck.check(dto.getTitle(), dto.getContentHtml());
        }
        // visibility=4 密码必填（明文不落日志）
        if (dto.getVisibility() == VisibilityEnum.PASSWORD
                && dto.getAccessPassword() != null && dto.getAccessPassword().isBlank()) {
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 插图归属校验：fileId 归属本人且 bizType=ARTICLE_IMG（防引用他人文件/违规图）
        if (hasImg) {
            checkImageOwnership(dto.getImages(), authorId);
        }
        // sanitize 三层铁律①②：标题/摘要剥标签纯文本；正文入库前白名单净化（占位符天然保留）
        String title = ArticleHtmlSanitizer.stripTags(dto.getTitle());
        String summary = ArticleHtmlSanitizer.stripTags(dto.getSummary());
        String contentHtml = hasText ? ArticleHtmlSanitizer.sanitize(dto.getContentHtml()) : dto.getContentHtml();
        // DFA 同步闸门（§4.2.1 ①，zone=2 作家分区跳 DFA 全量人工）：SSOT 决策口径 HIGH 拦/MID 仅留痕
        String assembledText = SubmitTextAssembler.assemble(title, contentHtml == null ? "" : contentHtml);
        if (zone != ZoneEnum.WRITER && hasText) {
            DfaHitResult hitResult = dfaFacade.check(DfaScenes.ARTICLE, assembledText);
            DfaPublishGate.DfaGateDecision gate = DfaPublishGate.decide(DfaScenes.ARTICLE, hitResult);
            if (gate.blocked()) {
                // HIGH（黑名单词）同步拦截，错误码统一 70001
                throw new BizException(ErrorCode.SENSITIVE_WORD_DETECTED);
            }
            if (gate.midMark()) {
                // MID（观察词）仅标记不拦截：留痕日志，异步层送审口径由 review 消费端收口
                log.info("DFA MID 命中仅标记不拦截：authorId={}，wordlibVer={}", authorId, hitResult.wordlibVer());
            }
        }
        // 落库主表：contentHash=SHA256(标题+\n+净化后正文)，对齐 review 文本指纹口径
        Article article = new Article()
                .setAuthorId(authorId)
                .setZone(zone)
                .setTitle(title)
                .setSummary(summary)
                .setContentHtml(contentHtml)
                .setCoverFileId(dto.getCoverFileId())
                .setCategory(dto.getCategory())
                .setStatus(ArticleStatusEnum.PENDING_REVIEW)
                .setVisibility(dto.getVisibility())
                .setViewCnt(0L).setLikeCnt(0L).setCommentCnt(0L)
                .setContentHash(SubmitTextAssembler.contentHash(assembledText));
        if (dto.getVisibility() == VisibilityEnum.PASSWORD) {
            article.setAccessPassword(passwordCrypto.encode(dto.getAccessPassword()));
        }
        articleMapper.insert(article);
        // 配图落 article_image（发布即挂 article_id，对齐权威落本表）
        if (hasImg) {
            saveArticleImages(dto.getImages(), article.getId(), null);
        }
        // 步骤A2：last-hash 免审三分支判定（§4.2.1 ②，zone=2/纯图文章不判定直接送审；
        // 含图文章图片连带校验（4-3.4）：聚合指纹=全部配图 sha256 排序拼接后再 sha256，键存在+指纹一致+PASS
        // 三者齐备才计入免审——缺任一即照常送审（免文不免图，宁多审不漏审；新文章首次发布键必缺失，天然送审）；
        // bizStatus=1 待审非驳回/下架态、delFlag=0，状态排除天然通过）
        long wordlibVer = dfaFacade.currentVersion(DfaScenes.ARTICLE);
        String aggregatedImgHash = hasImg ? aggregatedImgHash(dto.getImages()) : null;
        LastHashExemptChecker.ExemptDecision exempt = (zone != ZoneEnum.WRITER && hasText)
                ? LastHashExemptChecker.decide(wordlibVer,
                        ModerationConstant.BIZ_TYPE_ARTICLE, DfaScenes.ARTICLE, article.getId(),
                        article.getContentHash(), ArticleStatusEnum.PENDING_REVIEW.getCode(), 0,
                        !hasImg || LastHashExemptChecker.imgReReviewPassed(
                                wordlibVer, article.getId(), aggregatedImgHash, lastHashRedisAdapter),
                        lastHashRedisAdapter)
                : LastHashExemptChecker.ExemptDecision.SUBMIT;
        if (exempt == LastHashExemptChecker.ExemptDecision.REJECT_HINT) {
            // 内容未修改仍将驳回：前置提示不落库不发 MQ（事务回滚连带 insert 回滚，省一次注定失败的机审）
            throw new BizException(ErrorCode.REVIEW_REJECTED);
        }
        if (exempt == LastHashExemptChecker.ExemptDecision.EXEMPT_PASS) {
            // 免审直落：CAS 1→2 已发布（等价架构口径 0→2，insert 后晋级因免审键需 articleId），不发 submitted；
            // 免文不免图已由 imgReReviewOk 门控收口（EXEMPT_PASS 仅在配图复审已 PASS 时可达，无需再发 image.submit，
            // 对齐 LastHashExemptChecker 契约口径「免审直落不发 MQ」）；content.published 事件随板块四 [4.2] 补契约后接通
            articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, article.getId())
                    .eq(Article::getStatus, ArticleStatusEnum.PENDING_REVIEW)
                    .set(Article::getStatus, ArticleStatusEnum.PUBLISHED));
            return article.getId();
        }
        // 步骤A3：照常送审——afterCommit 发单条 content.submitted（事件体不含正文，review 经 ContentClient 回查）
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
     * 编辑文章：归属（本人或 ADMIN）→ 方案B（status=2 传入正文域字段即拒，防借编辑绕过复审）→
     * 分区权限校验 → 非 null 字段 sanitize 后增量更新 → 转私密 afterCommit 发 content.unpublished
     *
     * @param id         文章ID
     * @param dto        编辑入参（非 null 字段生效；配图 images 非 null 整体重挂）
     * @param operatorId 操作人ID（归属/权限校验基准）
     * @throws BizException ARTICLE_NOT_FOUND 不存在 / NO_PERMISSION 越权 / ARTICLE_PARAM_INVALID 方案B 拒改或密码缺失
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, ArticleUpdateDTO dto, Long operatorId) {
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
        upd.setCategory(dto.getCategory());
        upd.setZone(dto.getZone());
        boolean toPrivate = false;
        boolean toPublic = false;
        if (dto.getVisibility() != null) {
            // 转公开（visibility 2/3/4→1 且 status=2）不重审（[4.1]：方案B 禁改正文 + content_hash 未变，
            // 原审核结论仍有效）；走独立 CAS 收口不入 upd 通用集合（status 不变，禁与「4-下架终态」流转混淆）
            toPublic = published && dto.getVisibility() == VisibilityEnum.PUBLIC
                    && article.getVisibility() != VisibilityEnum.PUBLIC;
            if (!toPublic) {
                upd.setVisibility(dto.getVisibility());
                if (dto.getVisibility() == VisibilityEnum.PASSWORD) {
                    // 密码访问必须携带新密码（BCrypt 落库）
                    if (dto.getAccessPassword() == null || dto.getAccessPassword().isBlank()) {
                        throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
                    }
                    upd.setAccessPassword(passwordCrypto.encode(dto.getAccessPassword()));
                }
                // 转非公开（已发布文 → PRIVATE）隐私红线：必发 content.unpublished
                toPrivate = published && dto.getVisibility() == VisibilityEnum.PRIVATE
                        && article.getVisibility() != VisibilityEnum.PRIVATE;
            }
        }
        // 配图整体重挂（仅 status=0/3；null=不修改）
        if (dto.getImages() != null) {
            checkImageOwnership(dto.getImages(), article.getAuthorId());
            articleImageMapper.delete(new LambdaQueryWrapper<ArticleImage>()
                    .eq(ArticleImage::getArticleId, id));
            saveArticleImages(dto.getImages(), id, null);
        }
        articleMapper.updateById(upd);
        if (toPublic) {
            // 转公开 CAS（where status=2，MP 自动拼 del_flag=0）：仅已发布态可变更，并发下架/删除时 fail-fast
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
     * 驳回修改重提：仅作者本人 → 状态机白名单预校验（禁 2→1）→ CAS 3→1（并发兜底）
     *
     * @param articleId  文章ID
     * @param operatorId 操作人ID（须为作者本人）
     * @throws BizException ARTICLE_NOT_FOUND 不存在 / NO_PERMISSION 非本人 / ARTICLE_PARAM_INVALID 状态机拒绝或 CAS affected=0
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submit(Long articleId, Long operatorId) {
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
        // 下架审核缓存失效（[3.4] 方案 A：last-hash 精确删 + text/img-result SCAN，fail-safe 不阻断主流程，
        // 残留键 TTL 兜底；原板块八 TODO 提前在本板块收口）
        unpublishedCacheCleaner.invalidateArticle(articleId, article.getContentHash());
        // 作者侧操作日志：标题为 UGC 自由文本，剥 \r\n 防日志伪造注入（对齐 review §七.1 落日志口径）
        log.info("文章软删，articleId={}，operatorId={}，title={}",
                articleId, operatorId, LogSanitizeUtil.sanitizeForLog(article.getTitle()));
        // 软删隐私红线：afterCommit 必发 content.unpublished（search/ai 移除残留）
        publishUnpublishedAfterCommit(article, ContentBizConstant.UNPUBLISH_REASON_DELETE);
    }

    /**
     * 文章详情（visibility 四级判定链，§6.4）：非本人读非发布态按不存在处理不暴露存在性；
     * PRIVATE 非本人拒 / FOLLOWERS 降级宁严勿松 / PASSWORD 元信息可见正文不带出；配图占位符渲染回显
     *
     * @param articleId 文章ID
     * @param viewerId  查看者ID（null=匿名，仅可读发布态）
     * @return 详情 VO（PASSWORD 未验密时 contentHtml=null 且 needPassword=true；
     *         验密凭证标记命中则放行正文，[4.2]）
     * @throws BizException ARTICLE_NOT_FOUND 不存在/非发布态/可见性拦截
     */
    @Override
    public ArticleDetailVO getDetail(Long articleId, Long viewerId) {
        Article article = requireArticle(articleId);
        boolean owner = viewerId != null && article.getAuthorId().equals(viewerId);
        // 非本人读非发布态 → 按不存在处理（不暴露草稿/驳回/下架存在性）
        if (!owner && article.getStatus() != ArticleStatusEnum.PUBLISHED) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        VisibilityEnum visibility = article.getVisibility();
        String contentHtml = article.getContentHtml();
        boolean needPassword = false;
        if (!owner) {
            switch (visibility) {
                case PRIVATE -> {
                    // 仅自己：非本人按不存在，不暴露存在性
                    throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
                }
                case FOLLOWERS -> {
                    // 仅关注者（[4.1]）：回查 social 关注关系（短 TTL 缓存 ±10% 抖动，fail-fast 不静默放行）；
                    // 非关注者按不存在处理，不暴露存在性
                    if (!followRelationCache.isFollowing(article.getAuthorId(), viewerId)) {
                        throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
                    }
                }
                case PASSWORD -> {
                    // 密码访问（[4.2]）：验密凭证标记命中（10min 窗口内已通过 verify-password）放行正文；
                    // 未验密元信息可见，正文不带出，前端引向 verify-password 端点
                    boolean pwdGranted = stringRedisTemplate.hasKey(
                            ContentBizConstant.PWD_PASS_MARK_KEY_PREFIX + viewerId + ":" + articleId);
                    if (!pwdGranted) {
                        contentHtml = null;
                        needPassword = true;
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
            contentHtml = renderImages(contentHtml, images);
        }
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
                .setNeedPassword(needPassword)
                .setViewCnt(article.getViewCnt())
                .setLikeCnt(article.getLikeCnt())
                .setCommentCnt(article.getCommentCnt())
                .setTagNames(Collections.emptyList())
                .setImages(images.stream().map(this::toImageVO).collect(Collectors.toList()))
                .setCreateTime(article.getCreateTime())
                .setUpdateTime(article.getUpdateTime());
    }

    /**
     * 公开文章列表（匿名可读口径：status=2 且 visibility=1；select 排除 content_html 大字段延迟加载）
     *
     * @param zone     分区过滤（null=不限）
     * @param category 分类过滤（null/空白=不限）
     * @param pageNo   页码（1 起）
     * @param pageSize 页大小
     * @return 文章卡片列表（publishTime 取 update_time 发布时刻口径）
     */
    @Override
    public List<ArticleCardVO> listPublic(Integer zone, String category, int pageNo, int pageSize) {
        // 公开读仅 status=2 且 visibility=1（匿名可见）；select 排除 content_html 大字段延迟加载
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .select(Article.class, i -> !"content_html".equals(i.getColumn()))
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                .eq(zone != null, Article::getZone, zone)
                .eq(category != null && !category.isBlank(), Article::getCategory, category)
                .orderByDesc(Article::getUpdateTime)
                .last("limit " + (long) Math.max(0, pageNo - 1) * pageSize + "," + pageSize);
        return articleMapper.selectList(wrapper).stream().map(this::toCardVO).collect(Collectors.toList());
    }

    /**
     * 热门文章列表（view_cnt 倒序直查，公开读同口径；缓存三防随板块五接入 Redis）
     *
     * @param pageSize 取前 N 条
     * @return 文章卡片列表
     */
    @Override
    public List<ArticleCardVO> listHot(int pageSize) {
        // 热门：view_cnt 倒序直查；缓存三防（互斥重建/空值短缓存/TTL±10%抖动）随板块五 feed 接入 Redis
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .select(Article.class, i -> !"content_html".equals(i.getColumn()))
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getVisibility, VisibilityEnum.PUBLIC)
                .orderByDesc(Article::getViewCnt)
                .last("limit " + pageSize);
        return articleMapper.selectList(wrapper).stream().map(this::toCardVO).collect(Collectors.toList());
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
     * 插图归属校验：fileId 归属本人且 bizType=ARTICLE_IMG（fail-fast，防引用他人文件/违规图）
     *
     * @param images   待校验配图列表
     * @param authorId 期望归属用户ID
     */
    private void checkImageOwnership(List<ArticleImageDTO> images, Long authorId) {
        for (ArticleImageDTO img : images) {
            FileOwnerDTO ownerInfo = fileClient.getFileInfo(img.getFileId()).getData();
            if (ownerInfo == null
                    || !authorId.equals(ownerInfo.getOwnerId())
                    || !ContentBizConstant.FILE_BIZ_TYPE_ARTICLE_IMG.equals(ownerInfo.getBizType())) {
                throw new BizException(ErrorCode.ARTICLE_IMG_REFUSED);
            }
        }
    }

    /**
     * 配图批量落 article_image（对齐权威落本表；article_id/draft_id 二选一）
     *
     * @param images    配图列表
     * @param articleId 正式文章ID（草稿场景传 null）
     * @param draftId   草稿ID（发布场景传 null）
     */
    private void saveArticleImages(List<ArticleImageDTO> images, Long articleId, Long draftId) {
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
    }

    /**
     * 注册事务提交后发送 content.submitted（事务顺序铁律：本地事务提交 → 事件发送，失败重投/DLQ）；
     * 事件体不含正文，review 消费端按 bizType+bizId+scene 定位任务后经 ContentClient 回查
     *
     * @param articleId   文章ID（事件 bizId）
     * @param contentHash 送审文本指纹（SubmitTextAssembler 口径：标题+分隔符+正文 SHA-256）
     */
    private void registerSubmitEventAfterCommit(Long articleId, String contentHash) {
        TextSubmitEvent event = new TextSubmitEvent();
        event.setModType(ModerationConstant.MOD_TYPE_AUTO);
        event.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        event.setBizId(articleId);
        event.setScene(DfaScenes.ARTICLE);
        event.setContentHash(contentHash);
        event.setRetryCount(0);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                contentEventProducer.sendSubmitted(event);
            }
        });
    }

    /**
     * 计算配图聚合指纹（4-3.4 口径，必须与 review 侧复审任务 content_hash 完全一致，错配事件被消费端丢弃）：
     * 按发布入参登记顺序取 fileId → FileClient 批量查 file_meta.sha256（审核链路读 fail-fast）→
     * sha256 字符串字典序排序拼接 → 再整体做一次 SHA-256（复用 SubmitTextAssembler.contentHash 二次哈希口径）
     *
     * @param images 发布入参配图列表（fileId 登记顺序）
     * @return 64 位小写十六进制聚合指纹
     * @throws BizException ARTICLE_IMG_REFUSED 指纹缺图（脏数据聚合不完整会架空图片复审，fail-fast 拒绝发布）
     */
    private String aggregatedImgHash(List<ArticleImageDTO> images) {
        List<String> fileIds = images.stream().map(ArticleImageDTO::getFileId).toList();
        Map<String, String> shaMap = fileClient.batchGetSha256(fileIds).getData();
        if (shaMap == null || shaMap.size() < fileIds.stream().distinct().count()) {
            // 指纹缺一不可（缺失=file_meta 脏数据/已删，聚合口径不完整），fail-fast 拒绝发布
            throw new BizException(ErrorCode.ARTICLE_IMG_REFUSED);
        }
        // sha256 字典序排序拼接后二次 SHA-256（对齐 LastHashExemptChecker/review 侧聚合口径）
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
     * 注册 afterCommit 发送 content.published（转公开不重审场景，[4.1]）：
     * 事件体仅携指纹（bizType/bizId/contentHash），消费方按需门控回查权威态
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
     * 占位符渲染：{img:file_id} → 带 align class 的 <img>（对齐权威=article_image.align）
     * 未知图（无记录/无 URL）保留占位符降级，不破坏正文结构
     *
     * @param html   净化后正文（含占位符）
     * @param images 文章配图列表（提供顺序号/对齐权威）
     * @return 渲染后正文（URL 已转义防属性注入）
     */
    private String renderImages(String html, List<ArticleImage> images) {
        List<String> fileIds = images.stream().map(ArticleImage::getFileId).toList();
        Map<String, String> urlMap = fileClient.batchGetUrls(fileIds).getData();
        if (urlMap == null) {
            urlMap = Collections.emptyMap();
        }
        Matcher matcher = ContentBizConstant.IMG_PLACEHOLDER_PATTERN.matcher(html);
        StringBuilder sb = new StringBuilder(html.length());
        while (matcher.find()) {
            String fileId = matcher.group(1);
            ArticleImage img = images.stream()
                    .filter(i -> fileId.equals(i.getFileId()))
                    .findFirst().orElse(null);
            String url = urlMap.get(fileId);
            String replacement;
            if (img == null || img.getAlign() == null || url == null || url.isEmpty()) {
                replacement = Matcher.quoteReplacement(matcher.group(0));
            } else {
                String safeUrl = url.replace("&", "&amp;").replace("\"", "%22");
                replacement = "<img class=\"" + ContentBizConstant.RENDERED_IMG_CLASS_PREFIX
                        + img.getAlign().name().toLowerCase() + "\" src=\"" + safeUrl + "\" alt=\"\">";
            }
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
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
     * 文章实体 → 卡片 VO（publishTime 取 update_time 的发布时刻口径）
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