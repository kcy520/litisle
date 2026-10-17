package com.litisle.litislecontent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.constant.ModerationConstant;
import com.litisle.litisleapi.dto.moderation.PunishStatusDTO;
import com.litisle.litisleapi.dfa.DfaFacade;
import com.litisle.litisleapi.dfa.DfaScenes;
import com.litisle.litisleapi.event.content.ContentPublishedEvent;
import com.litisle.litisleapi.event.content.ContentUnpublishedEvent;
import com.litisle.litisleapi.event.moderation.ModerationAppealResultEvent;
import com.litisle.litisleapi.event.user.UserBannedEvent;
import com.litisle.litisleapi.event.moderation.ModerationResultEvent;
import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litisleapi.feign.ReviewClient;
import com.litisle.litisleapi.moderation.LastHashStore;
import com.litisle.litislecontent.cache.UnpublishedCacheCleaner;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.dfa.ContentLastHashRedisAdapter;
import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.mq.ContentEventProducer;
import com.litisle.litislecontent.service.ArticleReviewWriteBackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 文章审核结果回写服务实现（板块三 3-5，§4.5.2）。
 *
 * 【回写防线】
 * - 陈旧性防线：文本路事件体 contentHash 与文章表落库指纹比对，不一致（编辑后旧结果迟到）丢弃
 * - CAS 条件更新：状态白名单互斥（1→2 / 1→3 / 2→4 / 3→2 仅申诉），并发竞态 affected=0 静默
 * - 终态仅申诉可改：机审/人审 REJECT 禁 3→2；恢复发布只走申诉改判消费（5-4.5）
 * - 聚合判定：Redis Hash 槽暂存两路 verdict，REJECT 到达即驳回、PASS 需双路齐备（配图以
 *   article_image 表现状为准，防事件与落库竞态误判无图直放）
 *
 * 【幂等】CAS + 槽重写 + last-hash 重写 + 事件重发全部幂等，依赖业务幂等免 SETNX
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleReviewWriteBackServiceImpl implements ArticleReviewWriteBackService {

    /** 文章聚合 Mapper（状态 CAS 回写 + 回查落库指纹） */
    private final ArticleMapper articleMapper;

    /** 文章配图 Mapper（聚合判定按表现状判有无配图） */
    private final ArticleImageMapper articleImageMapper;

    /** 聚合槽读写（双链路 verdict 暂存） */
    private final StringRedisTemplate stringRedisTemplate;

    /** last-hash 免审键 Redis 读写适配器（契约层 LastHashStore 宿主注入实现） */
    private final ContentLastHashRedisAdapter lastHashRedisAdapter;

    /** DFA 词库版本查询（事件体 wordlibVer 缺省时的兜底快照） */
    private final DfaFacade dfaFacade;

    /** 内容域事件发布（published/unpublished 统一收口） */
    private final ContentEventProducer contentEventProducer;

    /** 下架场景缓存失效编排（last-hash + text-result + img-result 三键 SCAN 删除） */
    private final UnpublishedCacheCleaner unpublishedCacheCleaner;

    /** 消费参数（last-hash TTL / 抖动比例，外置 yml/Nacos 运营可调） */
    private final ContentProperties contentProperties;

    /** 处罚权威态回查（申诉改判前 fail-fast 校验作者封禁态，防晚到申诉翻转封禁连带结果） */
    private final ReviewClient reviewClient;

    /** 文件域客户端（4→2 申诉平反后连带恢复被隔离配图，file 侧不消费文章域申诉事件） */
    private final FileClient fileClient;

    /**
     * 处理审核结果事件（双链路聚合回写主流程，§4.5.2）
     *
     * @param event 审核结果事件
     */
    @Override
    public void handleResult(ModerationResultEvent event) {
        // 步骤1：bizType 分发（1-文章本侧消费；2-评论挂起留痕；其他留痕丢弃防串域）
        Integer bizType = event.getBizType();
        if (bizType == null) {
            log.warn("[3-5] result 事件缺 bizType，留痕丢弃：eventId={}", event.getEventId());
            return;
        }
        if (bizType == ModerationConstant.BIZ_TYPE_COMMENT) {
            // 评论模块未开发（板块六接线时消费），当前留痕丢弃防毒丸
            log.info("[3-5] 评论审核结果暂挂起（评论模块未开发），留痕丢弃：bizId={}", event.getBizId());
            return;
        }
        if (bizType != ModerationConstant.BIZ_TYPE_ARTICLE) {
            log.warn("[3-5] 非文章域 result 事件，留痕丢弃：bizType={} bizId={}", bizType, event.getBizId());
            return;
        }
        Integer verdict = event.getVerdict();
        // 步骤2：非终局忽略——SUSPICIOUS 已转人审（3-4.5），等人审终局再回写；未知码防注入丢弃
        if (verdict == null || verdict == ModerationConstant.VERDICT_SUSPICIOUS) {
            log.info("[3-5] 非终局 verdict 忽略：verdict={} eventId={}", verdict, event.getEventId());
            return;
        }
        if (verdict != ModerationConstant.VERDICT_PASS && verdict != ModerationConstant.VERDICT_REJECT) {
            log.warn("[3-5] 非法 verdict 丢弃：verdict={} eventId={}", verdict, event.getEventId());
            return;
        }
        // 步骤3：scene 分流定位聚合槽 field（ARTICLE→文本槽；ARTICLE_IMG_RE_REVIEW→图片槽）
        String field;
        if (DfaScenes.ARTICLE.equals(event.getScene())) {
            field = ContentBizConstant.ARTICLE_REVIEW_AGG_FIELD_TEXT;
        } else if (ModerationConstant.SCENE_ARTICLE_IMG_RE_REVIEW.equals(event.getScene())) {
            field = ContentBizConstant.ARTICLE_REVIEW_AGG_FIELD_IMG;
        } else {
            log.warn("[3-5] 文章域未知 scene 丢弃：scene={} eventId={}", event.getScene(), event.getEventId());
            return;
        }
        Long articleId = parseBizId(event.getBizId());
        if (articleId == null) {
            return;
        }
        // 步骤4：回查文章行（存在性 + 陈旧性防线 + 落库指纹取数）
        Article article = articleMapper.selectById(articleId);
        if (article == null || article.getDelFlag() == DelFlag.DELETED) {
            // 复审期间作者自删属正常业务终局，防毒丸 ack 丢弃
            log.info("[3-5] 文章不存在或已删除，正常终局丢弃：articleId={}", articleId);
            return;
        }
        if (ContentBizConstant.ARTICLE_REVIEW_AGG_FIELD_TEXT.equals(field)
                && !article.getContentHash().equals(event.getContentHash())) {
            // 陈旧性防线（§2.6 编辑竞态）：编辑后旧审核结果迟到，按结论作废丢弃（不触发新送审）
            log.warn("[3-5] 文本 result 陈旧（contentHash 与落库指纹错配）丢弃：articleId={}", articleId);
            return;
        }
        // 步骤5：终局结论写 last-hash 免审键（4-3.1，跟随事件到达而非聚合判定，fail-safe 不阻断）
        writeLastHash(event, article, field, verdict);
        // 步骤6：写聚合槽（HSET + 刷新 TTL；同路重复消费重写同值幂等）
        String aggKey = ContentBizConstant.ARTICLE_REVIEW_AGG_KEY_PREFIX + articleId;
        stringRedisTemplate.opsForHash().put(aggKey, field, String.valueOf(verdict));
        stringRedisTemplate.expire(aggKey, ContentBizConstant.ARTICLE_REVIEW_AGG_TTL_SECONDS,
                java.time.temporal.ChronoUnit.SECONDS);
        // 步骤7：聚合判定（REJECT 到达即驳回落 CAS；PASS 需文本 PASS 且无图或图片 PASS）
        tryAggregate(article, aggKey);
    }

    /**
     * 处理申诉改判结果事件（5-4.5：平台结论反转以最新为准）
     *
     * @param event 申诉改判事件
     */
    @Override
    public void handleAppeal(ModerationAppealResultEvent event) {
        // 步骤1：bizType 分发（同 result 事件口径；评论挂起/图片归 file 侧）
        Integer bizType = event.getBizType();
        if (bizType == null) {
            log.warn("[3-5] appeal 事件缺 bizType，留痕丢弃：eventId={}", event.getEventId());
            return;
        }
        if (bizType == ModerationConstant.BIZ_TYPE_COMMENT) {
            log.info("[3-5] 评论申诉改判暂挂起（评论模块未开发），留痕丢弃：bizId={}", event.getBizId());
            return;
        }
        if (bizType != ModerationConstant.BIZ_TYPE_ARTICLE) {
            log.warn("[3-5] 非文章域 appeal 事件，留痕丢弃：bizType={} bizId={}", bizType, event.getBizId());
            return;
        }
        Boolean appealPass = event.getAppealPass();
        Long articleId = parseBizId(event.getBizId());
        if (articleId == null) {
            return;
        }
        // 步骤2：改判不通过维持原判，无操作 ack（驳回维持，前置 REJECT_HINT 免审键保留继续服务）
        if (appealPass == null || !appealPass) {
            log.info("[3-5] 申诉维持原判：articleId={}", articleId);
            return;
        }
        // 步骤3：删 last-hash 免审键（5-4.5：键里可能存 REJECT 结论，不清则发布侧 REJECT_HINT 误提示）
        try {
            LastHashStore.remove(dfaFacade.currentVersion(DfaScenes.ARTICLE),
                    ModerationConstant.BIZ_TYPE_ARTICLE, DfaScenes.ARTICLE,
                    articleId, lastHashRedisAdapter);
        } catch (Exception e) {
            // fail-safe：删键失败残留等 TTL 过期（误提示窗口有限），不阻断恢复发布主流程
            log.error("[3-5] 申诉改判删 last-hash 失败（不阻断恢复发布），articleId={}", articleId, e);
        }
        // 步骤4：回查文章行（取归属作者/落库指纹/现态分流前提；复审期间自删属正常终局）
        Article article = articleMapper.selectById(articleId);
        if (article == null || article.getDelFlag() == DelFlag.DELETED) {
            log.info("[3-5] 申诉改判文章不存在或已删除，正常终局丢弃：articleId={}", articleId);
            return;
        }
        // 步骤4.5：改判前回查处罚权威态（[8.1] fail-fast：回查失败不放行）——防申诉链路晚到翻转
        // 封禁连带结果（封禁后 4→2 恢复曝光属治理漏洞；review 裁决端源头校验为双保险）。
        // 回查异常穿透交 consumer 延迟重试（对齐 Temporarily 通用纪律⑤禁静默吞错）
        try {
            Result<PunishStatusDTO> punishResult = reviewClient.getPunishStatus(article.getAuthorId());
            if (punishResult != null && punishResult.isSuccess() && punishResult.getData() != null
                    && Boolean.TRUE.equals(punishResult.getData().getBanned())) {
                log.warn("[3-5] 作者处罚封禁中，拒绝申诉改判：articleId={} authorId={}",
                        articleId, article.getAuthorId());
                return;
            }
        } catch (Exception e) {
            log.error("[3-5] 封禁态回查失败（不放行，交延迟重试），articleId={}", articleId, e);
            throw e;
        }
        // 步骤5：CAS 两向对称恢复（[8.1] allow-list：3→2 首发 / 4→2 下架，仅申诉来源，对齐 §2.3 状态机）
        // 5.1 首发 3→2
        long affected = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, articleId)
                .eq(Article::getStatus, ArticleStatusEnum.REJECTED)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .set(Article::getStatus, ArticleStatusEnum.PUBLISHED));
        boolean fromTakenDown = false;
        if (affected == 0) {
            // 5.2 复审下架 4→2（4 为终态，申诉改判是唯一业务恢复出口）
            affected = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                    .eq(Article::getId, articleId)
                    .eq(Article::getStatus, ArticleStatusEnum.TAKEN_DOWN)
                    .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                    .set(Article::getStatus, ArticleStatusEnum.PUBLISHED));
            fromTakenDown = affected > 0;
        }
        if (affected > 0) {
            // 步骤6：4→2 场景连带恢复被隔离配图（[8.1]：file 侧不消费文章域申诉事件，
            // 不连带则申诉平反后配图永久隔离；unquarantine 幂等，失败仅留痕不阻断改判主体）
            if (fromTakenDown) {
                unquarantineImages(articleId);
            }
            // 步骤7：发 content.published（恢复 search/ai(RAG) 索引对称性——与下架必发 unpublished 对偶；
            // RAG 门控回查 visibility 兕底）
            ContentPublishedEvent published = new ContentPublishedEvent();
            published.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
            published.setBizId(articleId);
            published.setContentHash(article.getContentHash());
            contentEventProducer.sendPublished(published);
            log.info("[3-5] 申诉改判恢复发布完成（{}→2）：articleId={}",
                    fromTakenDown ? "4" : "3", articleId);
            return;
        }
        // 步骤8：affected=0 现态分流——已是 PUBLISHED（重复消费幂等）静默；他态（编辑重提 3→1 等）留痕
        Article latest = articleMapper.selectById(articleId);
        if (latest != null && latest.getStatus() == ArticleStatusEnum.PUBLISHED) {
            log.info("[3-5] 文章已处发布态（重复消费幂等），静默：articleId={}", articleId);
            return;
        }
        log.warn("[3-5] 申诉改判 CAS 两向均未生效（现态={}），留痕：articleId={}",
                latest == null ? "已删除" : latest.getStatus(), articleId);
    }

    /**
     * 连带恢复被隔离配图（4→2 申诉平反专用）：逐张调 file unquarantine（幂等），
     * 单张失败仅 error 留痕不阻断（改判主体已完成，可由运营人工补偿或 TTL 对账兑底）
     *
     * @param articleId 文章 ID（反查 article_image 现存配图）
     */
    private void unquarantineImages(Long articleId) {
        List<String> fileIds = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                        .eq(ArticleImage::getArticleId, articleId)).stream()
                .map(ArticleImage::getFileId)
                .toList();
        for (String fileId : fileIds) {
            try {
                fileClient.unquarantine(fileId);
            } catch (Exception e) {
                log.error("[3-5] 配图恢复隔离失败（留痕不阻断），articleId={} fileId={}", articleId, fileId, e);
            }
        }
    }

    /**
     * 双链路聚合判定（§4.5.2：放行=文本 PASS 且（无配图或图片复审 PASS）；任一 REJECT→整文驳回）
     *
     * @param article 文章行（回查态，含落库指纹）
     * @param aggKey  聚合槽键（含 articleId）
     */
    private void tryAggregate(Article article, String aggKey) {
        Map<Object, Object> slots = stringRedisTemplate.opsForHash().entries(aggKey);
        String textVerdict = (String) slots.get(ContentBizConstant.ARTICLE_REVIEW_AGG_FIELD_TEXT);
        if (textVerdict == null) {
            // 文本路未终局：无论图片路结论如何都不聚合（文本是主线），等待 ack 返回
            return;
        }
        // 有无配图以 article_image 表现状为准（防发布竞态：事件声明有图但配图行已删等）
        boolean hasImg = articleImageMapper.selectCount(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getArticleId, article.getId())) > 0;
        String imgVerdict = (String) slots.get(ContentBizConstant.ARTICLE_REVIEW_AGG_FIELD_IMG);
        // 任一链路 REJECT → 整文驳回（立即聚合，不等另一路终局）
        if (String.valueOf(ModerationConstant.VERDICT_REJECT).equals(textVerdict)
                || String.valueOf(ModerationConstant.VERDICT_REJECT).equals(imgVerdict)) {
            finishReject(article, aggKey);
            return;
        }
        // 文本 PASS：无配图 → 文本 PASS 即整文 PASS；有配图等图片复审终局
        if (!hasImg) {
            finishPass(article, aggKey);
            return;
        }
        if (imgVerdict == null) {
            // 配图复审未终局，等待
            return;
        }
        if (String.valueOf(ModerationConstant.VERDICT_PASS).equals(imgVerdict)) {
            finishPass(article, aggKey);
        }
        // img=REJECT 已被上方拦截，此处不可达（防槽值被污染则静默，等 TTL 过期）
    }

    /**
     * 聚合放行终局：CAS 1→2 发布 + 发 content.published
     *
     * @param article 文章行
     * @param aggKey  聚合槽键（终局后删除）
     */
    private void finishPass(Article article, String aggKey) {
        // CAS 1→2（白名单 PENDING_REVIEW；del_flag=0 防复审期间作者自删误改）
        long affected = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, article.getId())
                .eq(Article::getStatus, ArticleStatusEnum.PENDING_REVIEW)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .set(Article::getStatus, ArticleStatusEnum.PUBLISHED));
        // 终局完成即删槽（防残留槽影响后续同文章编辑重发轮次）
        stringRedisTemplate.delete(aggKey);
        if (affected == 0) {
            // 并发终局/状态已被申诉等路径流转，静默幂等丢弃
            log.info("[3-5] 聚合放行 CAS 1→2 未生效（并发终局），静默：articleId={}", article.getId());
            return;
        }
        // 发 content.published（search 收录/ai 向量化/notification 通知，消费方幂等）
        ContentPublishedEvent published = new ContentPublishedEvent();
        published.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        published.setBizId(article.getId());
        published.setContentHash(article.getContentHash());
        contentEventProducer.sendPublished(published);
        log.info("[3-5] 聚合放行回写完成（1→2）：articleId={}", article.getId());
    }

    /**
     * 封禁连带下架（板块八 [8.2]，user.banned 联动消费）：该作者全部已发布文章批量置 4-下架，
     * 逐篇发 content.unpublished（search 移除索引/ai 移除向量）+ 本地缓存三键失效（防旧 PASS
     * 缓存绕过下架决定，口径同 ContentUnpublishedInvalidator 铁律）
     *
     * 【幂等设计】批量 CAS（ownerId+status=2+del_flag=0 → 4）与事件/失效均幂等；重试场景下
     * 候选改为「已发布+已下架」双态查询——CAS 已生效但事件未发的残留由重跑补发自愈
     * （对齐 finishReject 重试自愈口径）；已删除文章不参与（del_flag=0 过滤）
     *
     * @param event 用户封禁事件（user 侧封禁事务提交后发布，本方法无外部回查，失败均可安全重试）
     */
    @Override
    public void handleUserBanned(UserBannedEvent event) {
        // 步骤1：查候选（作者全部「已发布/已下架」未删除文章；含已下架=重试补发事件自愈窗口）
        List<Article> candidates = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .eq(Article::getOwnerId, event.getUserId())
                .in(Article::getStatus, ArticleStatusEnum.PUBLISHED, ArticleStatusEnum.TAKEN_DOWN)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED));
        if (candidates.isEmpty()) {
            log.info("[8.2] 封禁连带：作者无可下架文章（或已全部处理），userId={}", event.getUserId());
            return;
        }
        // 步骤2：批量 CAS 下架（条件含 status=2：仅发布态参与，避免重复置态；并发改态由条件兑底）
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getOwnerId, event.getUserId())
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .set(Article::getStatus, ArticleStatusEnum.TAKEN_DOWN));
        // 步骤3：逐篇发 unpublished + 缓存三键失效（事件与 cleaner 均幂等，重复执行收敛）
        for (Article article : candidates) {
            publishUnpublished(article);
            unpublishedCacheCleaner.invalidateArticle(article.getId(), article.getContentHash());
        }
        // 步骤4：审计留痕（@AuditLog 切面不覆盖 MQ 消费线程，同 [8.1] 口径显式落结构化日志）
        log.info("【审计日志】{{\"module\":\"CONTENT\",\"operation\":\"take_down\",\"operator\":\"system(mq)\",\"userId\":{},\"articleIds\":{},\"reason\":\"封禁连带下架（user.banned 联动消费）\"}}",
                event.getUserId(), candidates.stream().map(Article::getId).toList());
        log.info("[8.2] 封禁连带下架完成：userId={}，涉及文章={}", event.getUserId(), candidates.size());
    }

    /**
     * 聚合驳回终局：分流 1→3 首发驳回 / 2→4 复审下架（配套 unpublished 事件 + 缓存三键失效）
     *
     * @param article 文章行
     * @param aggKey  聚合槽键（终局后删除）
     */
    private void finishReject(Article article, String aggKey) {
        // 分流①：首发驳回 1→3（待审 → 驳回，作者改后 CAS 3→1 重提）
        long affected = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, article.getId())
                .eq(Article::getStatus, ArticleStatusEnum.PENDING_REVIEW)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .set(Article::getStatus, ArticleStatusEnum.REJECTED));
        // 终局完成即删槽
        stringRedisTemplate.delete(aggKey);
        if (affected > 0) {
            log.info("[3-5] 聚合驳回回写完成（首发 1→3）：articleId={}", article.getId());
            return;
        }
        // 分流②：已发布被复审下架 2→4（配套 punish_log action=3 由 review 侧 5-1 落）
        affected = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, article.getId())
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .set(Article::getStatus, ArticleStatusEnum.TAKEN_DOWN));
        if (affected > 0) {
            // 下架配套：发 content.unpublished（search 移除索引/ai 移除向量）+ 本地三键失效防旧缓存绕审
            auditTakeDown(article.getId(), "复审驳回下架（moderation.result 聚合判定）");
            publishUnpublished(article);
            unpublishedCacheCleaner.invalidateArticle(article.getId(), article.getContentHash());
            log.info("[3-5] 聚合驳回回写完成（复审下架 2→4）：articleId={}", article.getId());
            return;
        }
        // affected=0：回查现态自愈——TAKEN_DOWN 属延迟重投重试（事件未发/缓存未清）或并发下架，
        // 补发 unpublished + 重跑失效（事件与 cleaner 均幂等，多发无害）；审计留痕去重：仅首次落 CAS
        // 成功路径，重试自愈不重复落痕（幂等事件已补发）
        Article latest = articleMapper.selectById(article.getId());
        if (latest != null && latest.getStatus() == ArticleStatusEnum.TAKEN_DOWN) {
            publishUnpublished(article);
            unpublishedCacheCleaner.invalidateArticle(article.getId(), article.getContentHash());
            log.info("[3-5] 复审下架重试自愈（补发事件+重跑失效）：articleId={}", article.getId());
            return;
        }
        log.info("[3-5] 聚合驳回 CAS 未生效（现态={}），静默：articleId={}",
                latest == null ? "已删除" : latest.getStatus(), article.getId());
    }

    /**
     * 终局结论写 last-hash 免审键（4-3.1，跟随 result 事件到达写入，fail-safe 不阻断回写主流程）
     *
     * @param event   审核结果事件（取 contentHash/wordlibVer 快照）
     * @param article 文章行
     * @param field   聚合槽 field（text=文本链路 / img=图片复审链路）
     * @param verdict 终局判定码
     */
    private void writeLastHash(ModerationResultEvent event, Article article, String field, Integer verdict) {
        try {
            long ttl = lastHashTtlWithJitter();
            if (ContentBizConstant.ARTICLE_REVIEW_AGG_FIELD_TEXT.equals(field)) {
                // 文本主键：指纹取落库指纹（与事件体已比对一致）；版本快照优先事件体（判定时版本）
                long wordlibVer = event.getWordlibVer() != null
                        ? event.getWordlibVer()
                        : dfaFacade.currentVersion(DfaScenes.ARTICLE);
                LastHashStore.write(wordlibVer, ModerationConstant.BIZ_TYPE_ARTICLE, DfaScenes.ARTICLE,
                        article.getId(), article.getContentHash(), verdict, ttl, lastHashRedisAdapter);
                return;
            }
            // 图片连带键（4-3.4）：scene=ARTICLE_IMG_RE_REVIEW，指纹=配图聚合指纹（事件体 contentHash）
            if (event.getContentHash() == null || event.getContentHash().isBlank()) {
                log.warn("[3-5] 图片复审 result 缺聚合指纹，跳过连带免审键写入：articleId={}", article.getId());
                return;
            }
            long wordlibVer = event.getWordlibVer() != null
                    ? event.getWordlibVer()
                    : dfaFacade.currentVersion(DfaScenes.ARTICLE);
            LastHashStore.write(wordlibVer, ModerationConstant.BIZ_TYPE_ARTICLE,
                    ModerationConstant.SCENE_ARTICLE_IMG_RE_REVIEW,
                    article.getId(), event.getContentHash(), verdict, ttl, lastHashRedisAdapter);
        } catch (Exception e) {
            // fail-safe：免审键写失败宁多审不漏审（重发照常送审），error 留痕
            log.error("[3-5] last-hash 写入失败（不阻断回写主流程），articleId={}", article.getId(), e);
        }
    }

    /**
     * 下架配套事件发布（content.unpublished，reason=复审驳回下架；由 ContentEventProducer 统一收口补 base 字段）
     *
     * @param article 文章行
     */
    private void publishUnpublished(Article article) {
        ContentUnpublishedEvent unpublished = new ContentUnpublishedEvent();
        unpublished.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        unpublished.setBizId(article.getId());
        unpublished.setContentHash(article.getContentHash());
        unpublished.setReason(ContentBizConstant.UNPUBLISH_REASON_TAKEN_DOWN);
        contentEventProducer.sendUnpublished(unpublished);
    }

    /**
     * 复审下架审计留痕（[8.1] 2→4 治理动作）：与 @AuditLog 切面同格式 JSON 结构化输出（落点一致：
     * 切面本质 log.info("【审计日志】{}")）——切面 @annotation 拦截不覆盖同类内部私有调用与无登录态的
     * MQ 消费线程，故消费端治理动作显式落痕；操作人固定 system(mq)，责任链路由 review 侧 punish_log 对接
     *
     * @param articleId 文章 ID
     * @param reason    下架原因（结构化字段，随日志落盘）
     */
    private void auditTakeDown(Long articleId, String reason) {
        log.info("【审计日志】{{\"module\":\"CONTENT\",\"operation\":\"take_down\",\"operator\":\"system(mq)\",\"articleId\":{},\"reason\":\"{}\"}}",
                articleId, reason);
    }

    /**
     * last-hash TTL 附 ±抖动（±10%，防同批键同刻过期雪崩；7-1 缓存三防同口径）
     *
     * @return 抖动后的 TTL（秒）
     */
    private long lastHashTtlWithJitter() {
        long base = contentProperties.getModeration().getLastHashTtlHours() * 3600L;
        double ratio = contentProperties.getModeration().getTtlJitterRatio();
        return base + (long) (base * ratio * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
    }

    /**
     * 解析事件体 bizId（String 契约）为文章 ID；非法格式留痕丢弃（防毒丸）
     *
     * @param event 审核结果/申诉改判事件
     * @return 文章 ID；解析失败返回 null
     */
    private Long parseBizId(String bizId) {
        try {
            return Long.parseLong(bizId);
        } catch (NumberFormatException e) {
            log.warn("[3-5] bizId 非法（非数字）丢弃：bizId={}", bizId);
            return null;
        }
    }
}