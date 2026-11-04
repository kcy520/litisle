package com.litisle.litislecontent.service.support;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.dto.ArticleImageDTO;
import com.litisle.litislecontent.domain.entity.ArticleDraft;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.domain.vo.ArticleImageVO;
import com.litisle.litislecontent.mapper.ArticleDraftMapper;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import com.litisle.litisleapi.moderation.SubmitTextAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文章配图归属域支撑组件（原 ArticleServiceImpl 图片归属域私有方法收口）。
 *
 * 作用：
 * 统一收口 发布/建档/编辑 三条写链路共用的配图处理逻辑，与 ArticleImageRefSupport（file_ref 引用登记/解绑）
 * 分工协作——本组件管「article_image 行的挂载与草稿→文章归属切换」，引用登记/解绑仍委托 RefSupport：
 *   ① 插图归属校验：委托 ArticleImageRefSupport 批量校验（fileId 归属本人且 bizType=ARTICLE_IMG）；
 *   ② 配图批量落 article_image + 同一事务内逐张登记 file_ref 引用（fail-fast）；
 *   ③ 发布归属切换：草稿维度旧图行删除 + 差集解绑 DRAFT_IMAGE 旧引用（决策 D2）；
 *   ④ 草稿封面 → 文章封面引用交接（阶段8②，差集口径：沿用同一封面不解绑）；
 *   ⑤ 配图聚合指纹计算（「免文不免图」免审判定的图片 hash，须与 review 侧复审口径完全一致）；
 *   ⑥ 配图实体 → VO 转换（详情回显）
 *
 * 【大字段红线无关】本组件仅处理配图行与引用，不触碰 content_html
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class ArticleImageSupport {

    /** 文章配图 Mapper（配图挂载/详情回显反查数据源） */
    private final ArticleImageMapper articleImageMapper;

    /** 草稿快照 Mapper（阶段6：发布归属切换时草稿归属校验数据源） */
    private final ArticleDraftMapper articleDraftMapper;

    /** 文件服务 Feign 契约（批量取 sha256，配图聚合指纹计算用） */
    private final FileClient fileClient;

    /** 文章/草稿插图引用支撑组件（阶段6：批量归属校验 + file_ref bind/unbind 统一收口，消除双份重复实现） */
    private final ArticleImageRefSupport articleImageRefSupport;

    /**
     * 插图归属校验（阶段6 修 N+1 问题）：委托 ArticleImageRefSupport 批量校验
     * （fileId 必须归属本人且是文章配图类型，有问题立刻报错，防引用他人文件/违规图）；
     * 原来逐图远程查询（最多 50 次调用）改为批量查询一次往返
     *
     * @param images   待校验配图列表
     * @param authorId 期望归属用户ID
     */
    public void checkImageOwnership(List<ArticleImageDTO> images, Long authorId) {
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
    public void saveArticleImages(List<ArticleImageDTO> images, Long articleId, Long draftId, Long ownerId) {
        // 逐张插入配图行（article_id/draft_id 二选一挂载维度）
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
    public void switchDraftImageOwnership(Long draftId, List<ArticleImageDTO> images, Long authorId) {
        // 无来源草稿=直接发布，无切换语义
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
    public void unbindDraftCoverIfReplaced(Long draftId, String newCoverId, Long authorId) {
        // 无来源草稿=直接发布，无切换语义
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
     * 计算配图聚合指纹（4-3.4 同一规则，必须与 review 侧复审任务的 content_hash 完全一致，错配事件会被消费端丢弃）：
     * 按发布入参登记顺序取 fileId → FileClient 批量查 sha256（审核链路读失败立刻报错）→
     * sha256 字符串排序拼接 → 再整体做一次 SHA-256（复用 SubmitTextAssembler.contentHash 的二次哈希规则）
     *
     * @param images 发布入参配图列表（fileId 登记顺序）
     * @return 64 位小写十六进制聚合指纹
     * @throws BizException ARTICLE_IMG_REFUSED 指纹缺图（脏数据聚合不完整会架空图片复审，fail-fast 拒绝发布）
     */
    public String aggregatedImgHash(List<ArticleImageDTO> images) {
        // 按登记顺序取 fileId（聚合指纹与登记顺序无关，排序在 sha256 字符串层面做）
        List<String> fileIds = images.stream().map(ArticleImageDTO::getFileId).toList();
        // 批量取 sha256（一次往返，防逐图 N+1）
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
     * 配图实体 → VO（详情回显）
     *
     * @param image 配图实体
     * @return 配图 VO
     */
    public ArticleImageVO toImageVO(ArticleImage image) {
        return new ArticleImageVO()
                .setFileId(image.getFileId())
                .setPositionOrder(image.getPositionOrder())
                .setAlign(image.getAlign())
                .setWidth(image.getWidth())
                .setHeight(image.getHeight());
    }
}
