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

import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 文章审核结果回写服务实现（板块三 3-5，§4.5.2）。
 *
 * 作用：消费审核系统发回的结果事件，把结论写回文章状态（发布/驳回/下架）。
 *
 * 【安全防线】
 * - 防旧结果污染：事件里带的内容指纹与文章表当前指纹比对，不一致（编辑后旧结果迟到）直接丢弃
 * - 防并发改乱：改状态都带"当前必须是什么状态"的条件（1→2 / 1→3 / 2→4 / 3→2 仅申诉），
 *   条件不满足说明状态已被别的请求改走，本次静默放弃
 * - 终态只能申诉翻案：机审/人审驳回都不能把驳回改成发布；恢复发布只走申诉改判（5-4.5）
 * - 双链路聚合：文本审核和图片审核两路结论先暂存在 Redis 里，任一路驳回整文驳回、
 *   两路都通过才发布（有无配图以 article_image 表现状为准，防事件与落库竞态误判"无图直放"）
 *
 * 【重复消费安全】条件更新 + 槽重写 + 免审键重写 + 事件重发，重复执行都无副作用，不额外加去重锁
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleReviewWriteBackServiceImpl implements ArticleReviewWriteBackService {

    /** 文章 Mapper（带条件的状态回写 + 回查落库内容指纹） */
    private final ArticleMapper articleMapper;

    /** 文章配图 Mapper（聚合判定按表现状判有无配图） */
    private final ArticleImageMapper articleImageMapper;

    /** 聚合槽读写（Redis，暂存文本/图片两路审核结论） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 免审键（last-hash）的 Redis 读写适配器（LastHashStore 接口的实现宿主） */
    private final ContentLastHashRedisAdapter lastHashRedisAdapter;

    /** DFA 词库版本查询（事件体 wordlibVer 缺省时的兜底快照） */
    private final DfaFacade dfaFacade;

    /** 内容域事件发布（published/unpublished 统一收口） */
    private final ContentEventProducer contentEventProducer;

    /** 下架场景缓存失效编排（last-hash + text-result + img-result 三键 SCAN 删除） */
    private final UnpublishedCacheCleaner unpublishedCacheCleaner;

    /** 消费参数（last-hash TTL / 抖动比例，外置 yml/Nacos 运营可调） */
    private final ContentProperties contentProperties;

    /** 处罚状态回查（申诉改判前先查作者是否还在封禁中，防晚到的申诉把封禁连带下架的文章翻回发布） */
    private final ReviewClient reviewClient;

    /** 文件域客户端（4→2 申诉平反后连带恢复被隔离配图，file 侧不消费文章域申诉事件） */
    private final FileClient fileClient;

    /**
     * 处理审核结果事件（双链路聚合回写主流程，§4.5.2）。
     *
     * 作用：
     * 消费 moderation.result 事件，按 bizType/verdict/scene 逐层过滤后：
     * 回查文章执行陈旧性校验 → 写 last-hash 免审键 → 写聚合槽（文本/图片两路结论暂存）
     * → 聚合判定（任一路 REJECT 整文驳回；PASS 需文本 PASS 且无图或图片 PASS）。
     * 全程幂等，重复消费无副作用。
     *
     * @param event 审核结果事件（含 bizType/verdict/scene/contentHash 等字段）
     */
    @Override
    public void handleResult(ModerationResultEvent event) {

        /**
         * 审核结果回写流程（双链路聚合）：
         *
         * ① 过滤分发：只处理“文章域”的终局结论（PASS/REJECT）；
         *    评论模块未开发挂起留痕；非终局（已转人审）和非法值直接丢弃（防毒丸）。
         * ② 场景分流：文本审核结果 / 图片复审结果分别定位聚合槽的不同字段。
         * ③ 陈旧性防线：文本结果的内容指纹与数据库当前指纹不一致
         *    （文章被编辑过，旧结果迟到）→ 作废丢弃。
         * ④ 写免审键：终局结论写入 last-hash（下次发同样内容时用于免审判定），失败不阻断。
         * ⑤ 写聚合槽：结论暂存 Redis（文本/图片两路各一个字段，重复消费重写同值幂等）。
         * ⑥ 聚合判定：任一路驳回 → 整文驳回；放行需“文本通过 且（无配图 或 图片也通过）”。
         */

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
        // 步骤5：终局结论写 last-hash 免审键（4-3.1，结论随事件到达就写、不等聚合判定；写失败不阻断主流程）
        writeLastHash(event, article, field, verdict);
        // 步骤6：写聚合槽（写入结论 + 刷新过期时间；同一路重复消费重写同值，无副作用）
        String aggKey = ContentBizConstant.ARTICLE_REVIEW_AGG_KEY_PREFIX + articleId;
        stringRedisTemplate.opsForHash().put(aggKey, field, String.valueOf(verdict));
        stringRedisTemplate.expire(aggKey, ContentBizConstant.ARTICLE_REVIEW_AGG_TTL_SECONDS,
                TimeUnit.SECONDS);
        // 步骤7：聚合判定（任一路驳回就立即驳回落库；放行需文本通过且无配图，或配图复审也通过）
        tryAggregate(article, aggKey);
    }

    /**
     * 处理申诉改判结果事件（5-4.5：平台结论反转以最新为准）。
     *
     * 作用：
     * 消费申诉改判事件，仅处理"申诉通过"的文章域事件：删 last-hash 免审键 →
     * 回查文章行与作者封禁态（fail-fast）→ CAS 两向恢复发布（首发驳回 3→2 /
     * 复审下架 4→2）→ 下架场景连带恢复被隔离配图并发 content.published 事件。
     * 改判不通过维持原判，直接 ack。
     *
     * @param event 申诉改判事件（含 bizType/appealPass 等字段）
     */
    @Override
    public void handleAppeal(ModerationAppealResultEvent event) {

        /**
         * 申诉改判流程（平台结论反转以最新为准）：
         *
         * ① 过滤分发：只处理文章域事件；改判不通过维持原判，直接结束。
         * ② 删免审键：键里可能存“驳回”结论，不清则下次发布会误提示“改了也会被驳回”。
         * ③ 回查文章：不存在/已删除按正常终局丢弃。
         * ④ 封禁态校验：作者正被封禁时不放行（防晚到申诉翻转封禁连带结果）；
         *    回查失败也不放行，交延迟重试。
         * ⑤ CAS 两向恢复：首发驳回(3)→发布 或 复审下架(4)→发布（终态只有申诉能改）。
         * ⑥ 连带处理：复审下架恢复时，连带解除配图隔离；发 content.published 恢复搜索/AI 收录。
         * ⑦ 未生效分流：已是发布态（重复消费）静默；其他状态留痕。
         */

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
        // 步骤4.5：改判前先回查作者的处罚状态（[8.1] 查不到/查失败不放行）——防止晚到的申诉
        // 把"封禁连带下架"的文章翻回发布（封禁后还恢复曝光属于治理漏洞；review 裁决端已有源头校验，这里是双保险）。
        // 回查出错时把异常往外抛，交给 MQ 延迟重试（不允许静默吞错）
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
        // 步骤5：带条件的两向恢复（[8.1] 只允许两种：3→2 首发驳回恢复 / 4→2 下架恢复，仅申诉触发，对齐 §2.3 状态机）
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
            // 不连带恢复的话申诉平反后配图会永久隔离；恢复操作重复执行无副作用，失败只留日志不阻断改判主体）
            if (fromTakenDown) {
                unquarantineImages(articleId);
            }
            // 步骤7：发 content.published（让搜索/AI 索引恢复收录——和下架时必发 unpublished 成对；RAG 侧还有可见性回查兑底）兕底）
            ContentPublishedEvent published = new ContentPublishedEvent();
            published.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
            published.setBizId(articleId);
            published.setContentHash(article.getContentHash());
            contentEventProducer.sendPublished(published);
            log.info("[3-5] 申诉改判恢复发布完成（{}→2）：articleId={}",
                    fromTakenDown ? "4" : "3", articleId);
            return;
        }
        // 步骤8：两次都没改成说明状态已被别人改走——已是发布态（重复消费）静默；其他状态（如编辑重提 3→1）留日志
        Article latest = articleMapper.selectById(articleId);
        if (latest != null && latest.getStatus() == ArticleStatusEnum.PUBLISHED) {
            log.info("[3-5] 文章已处发布态（重复消费幂等），静默：articleId={}", articleId);
            return;
        }
        log.warn("[3-5] 申诉改判 CAS 两向均未生效（现态={}），留痕：articleId={}",
                latest == null ? "已删除" : latest.getStatus(), articleId);
    }

    /**
     * 连带恢复被隔离配图（4→2 申诉平反专用）：逐张调 file 解除隔离（重复调用无副作用），
     * 单张失败只记 error 日志不中断（改判主体已完成，剩余可由运营人工补偿或对账任务兑底）
     *
     * @param articleId 文章 ID（反查 article_image 现存配图）
     */
    private void unquarantineImages(Long articleId) {
        // 步骤1：反查文章现存配图文件ID列表
        List<String> fileIds = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                        .eq(ArticleImage::getArticleId, articleId)).stream()
                .map(ArticleImage::getFileId)
                .toList();
        // 步骤2：逐张调 file 解除隔离（重复调用无副作用），单张失败只留日志不中断
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
        // 步骤1：读取聚合槽两路结论（文本/图片暂存值）
        Map<Object, Object> slots = stringRedisTemplate.opsForHash().entries(aggKey);
        String textVerdict = (String) slots.get(ContentBizConstant.ARTICLE_REVIEW_AGG_FIELD_TEXT);
        if (textVerdict == null) {
            // 文本路未终局：无论图片路结论如何都不聚合（文本是主线），等待 ack 返回
            return;
        }
        // 步骤2：有无配图以 article_image 表现状为准（防发布竞态：事件声明有图但配图行已删等）
        boolean hasImg = articleImageMapper.selectCount(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getArticleId, article.getId())) > 0;
        String imgVerdict = (String) slots.get(ContentBizConstant.ARTICLE_REVIEW_AGG_FIELD_IMG);
        // 步骤3：任一链路 REJECT → 整文驳回（立即聚合，不等另一路终局）
        if (String.valueOf(ModerationConstant.VERDICT_REJECT).equals(textVerdict)
                || String.valueOf(ModerationConstant.VERDICT_REJECT).equals(imgVerdict)) {
            finishReject(article, aggKey);
            return;
        }
        // 步骤4：文本 PASS：无配图 → 文本 PASS 即整文 PASS；有配图等图片复审终局
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
     * 聚合放行终局：带条件地把状态 1→2 改为发布 + 发 content.published
     *
     * @param article 文章行
     * @param aggKey  聚合槽键（终局后删除）
     */
    private void finishPass(Article article, String aggKey) {
        // 步骤1：条件更新 1→2（只有"待审核"状态才改；且未删除，防复审期间作者自删被误改）
        long affected = articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getId, article.getId())
                .eq(Article::getStatus, ArticleStatusEnum.PENDING_REVIEW)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .set(Article::getStatus, ArticleStatusEnum.PUBLISHED));
        // 步骤2：终局完成即删槽（防残留槽影响后续同文章编辑重发轮次）
        stringRedisTemplate.delete(aggKey);
        if (affected == 0) {
            // 并发终局/状态已被申诉等路径流转，静默丢弃（重复消费无副作用）
            log.info("[3-5] 聚合放行 CAS 1→2 未生效（并发终局），静默：articleId={}", article.getId());
            return;
        }
        // 步骤3：发 content.published（搜索收录/AI 向量化/通知，消费方重复消费无副作用）
        ContentPublishedEvent published = new ContentPublishedEvent();
        published.setBizType(ModerationConstant.BIZ_TYPE_ARTICLE);
        published.setBizId(article.getId());
        published.setContentHash(article.getContentHash());
        contentEventProducer.sendPublished(published);
        log.info("[3-5] 聚合放行回写完成（1→2）：articleId={}", article.getId());
    }

    /**
     * 封禁连带下架（板块八 [8.2]，消费 user.banned 事件联动）：把该作者全部已发布文章批量改为 4-下架，
     * 逐篇发 content.unpublished（搜索移除索引/AI 移除向量）+ 本地缓存三键失效
     * （防止旧的"审核通过"缓存绕过下架决定，与 ContentUnpublishedInvalidator 同规则）
     *
     * 【重复消费安全】批量条件更新与事件/缓存失效重复执行都无副作用；重试场景下
     * 候选改为「已发布+已下架」双态都查——上次已改成下架但事件没发出去的，这次重跑补发，自愈
     * （与 finishReject 的重试自愈同做法）；已删除文章不参与（del_flag=0 过滤）
     *
     * @param event 用户封禁事件（user 侧封禁事务提交后发布，本方法无外部回查，失败均可安全重试）
     */
    @Override
    public void handleUserBanned(UserBannedEvent event) {

        /**
         * 封禁连带下架流程（用户被封禁 → 全部已发布文章下架）：
         *
         * ① 查候选：该作者所有“已发布/已下架”且未删除的文章
         *    （含已下架是为了重试时补发漏发的事件，自愈窗口）。
         * ② 批量 CAS 下架：发布态文章统一改为“已下架”（条件并发兑底）。
         * ③ 逐篇善后：发 content.unpublished（搜索/AI 移除索引与向量）
         *    + 清理审核缓存三键（防旧“通过”缓存绕过下架决定）。
         * ④ 审计留痕：显式落结构化日志（MQ 消费线程无登录态，审计切面拦不到）。
         */

        // 步骤1：查候选（作者全部「已发布/已下架」未删除文章；含已下架=重试补发事件自愈窗口）
        List<Article> candidates = articleMapper.selectList(new LambdaQueryWrapper<Article>()
                .eq(Article::getAuthorId, event.getUserId())
                .in(Article::getStatus, ArticleStatusEnum.PUBLISHED, ArticleStatusEnum.TAKEN_DOWN)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED));
        if (candidates.isEmpty()) {
            log.info("[8.2] 封禁连带：作者无可下架文章（或已全部处理），userId={}", event.getUserId());
            return;
        }
        // 步骤2：批量条件更新下架（条件含 status=2：只有发布态才改，避免重复置态；并发改态由条件保证安全）
        articleMapper.update(null, new LambdaUpdateWrapper<Article>()
                .eq(Article::getAuthorId, event.getUserId())
                .eq(Article::getStatus, ArticleStatusEnum.PUBLISHED)
                .eq(Article::getDelFlag, DelFlag.NOT_DELETED)
                .set(Article::getStatus, ArticleStatusEnum.TAKEN_DOWN));
        // 步骤3：逐篇发 unpublished + 缓存三键失效（事件与缓存清理重复执行无副作用）
        for (Article article : candidates) {
            publishUnpublished(article);
            unpublishedCacheCleaner.invalidateArticle(article.getId(), article.getContentHash());
        }
        // 步骤4：审计留痕（@AuditLog 切面拦不到 MQ 消费线程，与 [8.1] 一致显式落结构化日志）
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
        // 步骤1 分流①：首发驳回 1→3（待审 → 驳回；作者修改后重提时再由发布链路 3→1）
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
        // 步骤2 分流②：已发布被复审下架 2→4（配套 punish_log action=3 由 review 侧 5-1 落）
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
        // 步骤3：两次都没改成（affected=0）→ 回查现态自愈：已是下架态说明是延迟重投的重试
        // （上次事件没发/缓存没清）或并发下架，补发 unpublished + 重跑缓存失效（重复执行无副作用，多发无害）；
        // 审计留痕去重：只有首次真正改态成功才落审计，重试自愈不重复落痕
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
     * 终局结论写 last-hash 免审键（4-3.1，结论随事件到达就写入；写失败不阻断回写主流程）
     *
     * @param event   审核结果事件（取 contentHash/wordlibVer 快照）
     * @param article 文章行
     * @param field   聚合槽 field（text=文本链路 / img=图片复审链路）
     * @param verdict 终局判定码
     */
    private void writeLastHash(ModerationResultEvent event, Article article, String field, Integer verdict) {
        try {
            // 步骤1：计算 TTL（±10% 抖动，防同批键同刻过期雪崩）
            long ttl = lastHashTtlWithJitter();
            if (ContentBizConstant.ARTICLE_REVIEW_AGG_FIELD_TEXT.equals(field)) {
                // 步骤2：文本主键——指纹取落库指纹（与事件体已比对一致）；版本快照优先事件体（判定时版本）
                long wordlibVer = event.getWordlibVer() != null
                        ? event.getWordlibVer()
                        : dfaFacade.currentVersion(DfaScenes.ARTICLE);
                LastHashStore.write(wordlibVer, ModerationConstant.BIZ_TYPE_ARTICLE, DfaScenes.ARTICLE,
                        article.getId(), article.getContentHash(), verdict, ttl, lastHashRedisAdapter);
                return;
            }
            // 步骤3：图片连带键（4-3.4）——scene=ARTICLE_IMG_RE_REVIEW，指纹=配图聚合指纹（事件体 contentHash）
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
     * 复审下架审计留痕（[8.1] 2→4 治理动作）：按 @AuditLog 切面同款 JSON 格式输出（落点一致，
     * 切面本质也是打一条 log.info("【审计日志】")）——切面拦不到同类内部私有调用和没有登录态的
     * MQ 消费线程，所以消费端的治理动作在这里显式打日志；操作人固定记 system(mq)，
     * 后续追责链路由 review 侧 punish_log 对接
     *
     * @param articleId 文章 ID
     * @param reason    下架原因（结构化字段，随日志落盘）
     */
    private void auditTakeDown(Long articleId, String reason) {
        log.info("【审计日志】{{\"module\":\"CONTENT\",\"operation\":\"take_down\",\"operator\":\"system(mq)\",\"articleId\":{},\"reason\":\"{}\"}}",
                articleId, reason);
    }

    /**
     * 免审键 TTL 加 ±抖动（±10%，防止同一批键同一时刻集中过期引发缓存雪崩；7-1 缓存三防同规则）
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
     * @param bizId 事件体携带的业务 ID（String 形态）
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