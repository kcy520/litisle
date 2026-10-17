package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.dto.file.FileOwnerDTO;
import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.dto.ArticleImageDTO;
import com.litisle.litislecontent.domain.dto.DraftSaveDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleDraft;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.domain.vo.DraftCardVO;
import com.litisle.litislecontent.domain.vo.DraftDetailVO;
import com.litisle.litislecontent.domain.vo.DraftSaveVO;
import com.litisle.litislecontent.mapper.ArticleDraftMapper;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.service.DraftService;
import com.litisle.litislecontent.utils.ArticleHtmlSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 草稿域实现（快照 UPSERT/乐观锁/滚动清理，§6.2）。
 * <p>定位契约：articleId 传值→按文章定位最新快照；null→复用当前用户最新一条未关联文章的草稿
 * （DraftSaveVO.draftId 闭环，DTO 无 draftId 入参通道，多端并存由版本比对兜底）</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DraftServiceImpl implements DraftService {

    private final ArticleMapper articleMapper;

    private final ArticleDraftMapper articleDraftMapper;

    private final ArticleImageMapper articleImageMapper;

    private final FileClient fileClient;

    /** 草稿快照保留版数（同维度仅留最近 N 版，超出滚动清理最旧快照，防 longtext 膨胀） */
    @Value("${litisle.content.draft.keep-versions:10}")
    private int keepVersions;

    /**
     * 草稿快照保存（幂等，§6.2）：定位草稿+归属校验 → sanitize 收口 → baseVersion 严格按序四分支判定
     * （落后冲突/超前非法/同版本幂等/同版本递增）→ UPSERT → images 非 null 整体重挂 → afterCommit 滚动清理
     *
     * @param dto    保存入参（articleId=null 纯新草稿；baseVersion 乐观锁基准；images null=不修改）
     * @param userId 当前登录用户ID（归属校验基准）
     * @return 保存结果 {draftId, draftVersion}，前端以 draftVersion 作下次 baseVersion 形成闭环
     * @throws BizException ARTICLE_DRAFT_CONFLICT 版本落后 / ARTICLE_PARAM_INVALID 版本超前 / ARTICLE_NOT_FOUND 越权
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DraftSaveVO save(DraftSaveDTO dto, Long userId) {
        // 步骤1：定位草稿 + 归属校验（draft/article 的 author_id 比对当前登录用户，防横向越权）
        ArticleDraft draft = locateDraft(dto, userId);
        // 步骤2：sanitize 入库前收口（与 article 同款三层铁律，防存储型 XSS 经草稿链路入库）
        String title = ArticleHtmlSanitizer.stripTags(dto.getTitle());
        String summary = ArticleHtmlSanitizer.stripTags(dto.getSummary());
        String contentHtml = dto.getContentHtml() == null || dto.getContentHtml().isEmpty()
                ? dto.getContentHtml()
                : ArticleHtmlSanitizer.sanitize(dto.getContentHtml());
        // 步骤3：版本判定严格按序四分支（先拦截冲突/非法，再幂等/递增，防重复请求误入冲突分支）
        int serverVersion = draft == null ? 0 : draft.getDraftVersion();
        int cmp = dto.getBaseVersion().compareTo(serverVersion);
        if (cmp < 0) {
            // 分支③ baseVersion 落后：后写不覆盖先写，前端刷新后重试
            throw new BizException(ErrorCode.ARTICLE_DRAFT_CONFLICT);
        }
        if (cmp > 0) {
            // 分支④ baseVersion 超前：客户端版本非法（理论不可达，防御性拒绝）
            throw new BizException(ErrorCode.ARTICLE_PARAM_INVALID);
        }
        // 分支① 同版本且内容未变：幂等跳过（网络抖动重发同请求，不再递增版本；images 非 null 时纳入变更比对）
        if (draft != null && sameContent(draft, title, summary, contentHtml, dto)
                && imagesUnchanged(draft.getId(), dto.getImages())) {
            return new DraftSaveVO().setDraftId(draft.getId()).setDraftVersion(draft.getDraftVersion());
        }
        // 分支② 同版本且内容有变：UPSERT + draft_version+1
        int nextVersion = serverVersion + 1;
        if (draft == null) {
            draft = new ArticleDraft()
                    .setAuthorId(userId)
                    .setArticleId(dto.getArticleId())
                    .setDraftVersion(nextVersion);
        } else {
            draft.setDraftVersion(nextVersion);
        }
        draft.setTitle(title)
                .setSummary(summary)
                .setContentHtml(contentHtml)
                .setCoverFileId(dto.getCoverFileId())
                .setCategory(dto.getCategory())
                // visibility 可空=沿用草稿已有值（DTO 契约），避免自动保存误清空可见性
                .setVisibility(dto.getVisibility() == null ? draft.getVisibility() : dto.getVisibility());
        if (draft.getId() == null) {
            articleDraftMapper.insert(draft);
        } else {
            articleDraftMapper.updateById(draft);
        }
        // 步骤4：images 非 null=整体重挂（草稿图片暂存：article_image.draft_id 归属，article_id 空置；
        // 发布归属切换 draft_id→article_id 属板块三 [3.1] 发布编排，content 侧此处仅暂存）
        if (dto.getImages() != null) {
            remountDraftImages(dto.getImages(), draft.getId(), userId);
        }
        // 步骤5：注册 afterCommit 滚动清理（不进主事务，防 longtext 删除拉长保存 RT 与事务时长）
        cleanupOldSnapshotsAfterCommit(userId, dto.getArticleId());
        return new DraftSaveVO().setDraftId(draft.getId()).setDraftVersion(nextVersion);
    }

    /**
     * 定位草稿快照 + 归属校验（防横向越权）
     * <p>articleId 传值→校验文章归属后按文章定位最新快照；null→复用当前用户最新一条未关联文章的草稿</p>
     *
     * @param dto    保存入参
     * @param userId 当前登录用户ID
     * @return 最新草稿快照（无任何草稿时返回 null，由调用方走新建分支）
     */
    private ArticleDraft locateDraft(DraftSaveDTO dto, Long userId) {
        if (dto.getArticleId() == null) {
            // 纯新草稿链路：复用当前用户最新一条未关联文章的草稿（无则由调用方新建，draftId 闭环）
            return articleDraftMapper.selectOne(new LambdaQueryWrapper<ArticleDraft>()
                    .eq(ArticleDraft::getAuthorId, userId)
                    .isNull(ArticleDraft::getArticleId)
                    .orderByDesc(ArticleDraft::getDraftVersion)
                    .last("LIMIT 1"));
        }
        // 编辑已有文章草稿链路：先校验文章归属，防越权读写他人文章草稿（非本人按不存在处理）
        Article article = articleMapper.selectById(dto.getArticleId());
        if (article == null || !Objects.equals(article.getAuthorId(), userId)) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        ArticleDraft draft = articleDraftMapper.selectOne(new LambdaQueryWrapper<ArticleDraft>()
                .eq(ArticleDraft::getArticleId, dto.getArticleId())
                .orderByDesc(ArticleDraft::getDraftVersion)
                .last("LIMIT 1"));
        // 草稿快照归属兜底校验（正常与文章归属一致，防御性比对）
        if (draft != null && !Objects.equals(draft.getAuthorId(), userId)) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        return draft;
    }

    /**
     * 内容变更比对（幂等判定）：全字段逐一比对，任一不一致即视为有变更
     *
     * @param draft       服务端最新快照
     * @param title       sanitize 后标题
     * @param summary     sanitize 后摘要
     * @param contentHtml sanitize 后正文
     * @param dto         保存入参
     * @return true=内容完全一致（幂等跳过，版本不递增）；false=有变更（递增版本）
     */
    private boolean sameContent(ArticleDraft draft, String title, String summary,
                                String contentHtml, DraftSaveDTO dto) {
        // visibility 为 null 表示沿用草稿已有值，不构成变更
        return Objects.equals(draft.getTitle(), title)
                && Objects.equals(draft.getSummary(), summary)
                && Objects.equals(draft.getContentHtml(), contentHtml)
                && Objects.equals(draft.getCoverFileId(), dto.getCoverFileId())
                && Objects.equals(draft.getCategory(), dto.getCategory())
                && (dto.getVisibility() == null || Objects.equals(draft.getVisibility(), dto.getVisibility()));
    }

    /**
     * 草稿图片整体重挂（暂存）：先删该草稿旧图再按入参顺序落新图（DTO 契约：非 null=整体重挂）
     * <p>落库 article_id 空置仅挂 draft_id（DDL 已 nullable）；发布归属切换（draft_id→article_id
     * 回填并清空 draft_id）属板块三 [3.1] 发布编排；废弃草稿孤儿图片由 file 对账链路兑底（content 不直删）</p>
     *
     * @param images  新配图列表（DTO 校验 ≤50 张）
     * @param draftId 草稿快照ID（图片挂载锚点，单行 UPSERT 语义下恒定）
     * @param userId  当前登录用户ID
     */
    private void remountDraftImages(List<ArticleImageDTO> images, Long draftId, Long userId) {
        // 归属校验：fileId 归属本人且 bizType=ARTICLE_IMG（与 ArticleServiceImpl 同口径，防引用他人文件/违规图）
        checkImageOwnership(images, userId);
        // 整体重挂：物理删旧（article_image 无 del_flag）后按入参顺序插入
        articleImageMapper.delete(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getDraftId, draftId));
        for (ArticleImageDTO item : images) {
            articleImageMapper.insert(new ArticleImage()
                    .setArticleId(null)
                    .setDraftId(draftId)
                    .setFileId(item.getFileId())
                    .setPositionOrder(item.getPositionOrder())
                    .setAlign(item.getAlign())
                    .setWidth(item.getWidth())
                    .setHeight(item.getHeight()));
        }
    }

    /**
     * 插图归属校验：fileId 归属本人且 bizType=ARTICLE_IMG（fail-fast，与 ArticleServiceImpl 口径一致）
     *
     * @param images   待校验配图列表
     * @param authorId 当前登录用户ID
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
     * 图片变更比对（幂等判定补充）：images 为 null 视为不修改（不构成变更）；
     * 非 null 时与现有草稿图逐项全等比对（fileId/顺序号/对齐/宽高）
     *
     * @param draftId 草稿快照ID
     * @param images  保存入参配图（null=不修改）
     * @return true=图片未变（可幂等跳过）；false=有变更（走递增分支）
     */
    private boolean imagesUnchanged(Long draftId, List<ArticleImageDTO> images) {
        if (images == null) {
            return true;
        }
        List<ArticleImage> existing = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getDraftId, draftId));
        if (existing.size() != images.size()) {
            return false;
        }
        // 两侧均按 positionOrder+fileId 统一排序后逐项比对，避免入参顺序抖动误判为变更
        existing.sort(Comparator.comparing(ArticleImage::getPositionOrder)
                .thenComparing(ArticleImage::getFileId));
        List<ArticleImageDTO> sorted = images.stream()
                .sorted(Comparator.comparing(ArticleImageDTO::getPositionOrder)
                        .thenComparing(ArticleImageDTO::getFileId))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            ArticleImage oldImg = existing.get(i);
            ArticleImageDTO newImg = sorted.get(i);
            if (!Objects.equals(oldImg.getFileId(), newImg.getFileId())
                    || !Objects.equals(oldImg.getPositionOrder(), newImg.getPositionOrder())
                    || !Objects.equals(oldImg.getAlign(), newImg.getAlign())
                    || !Objects.equals(oldImg.getWidth(), newImg.getWidth())
                    || !Objects.equals(oldImg.getHeight(), newImg.getHeight())) {
                return false;
            }
        }
        return true;
    }

    /**
     * afterCommit 滚动清理最旧快照（同维度仅留最近 keepVersions 版，防 longtext 膨胀）
     * <p>注册在事务提交点之后执行——不进主事务（清理失败不回滚已提交的保存），
     * 防 longtext 删除拉长保存 RT；主链路单行 UPSERT 正常仅 1 行，
     * 本清理为同维度历史残留行（并发/异常插入）的防膨胀兑底，仅留最近 N 版口径对齐细案</p>
     *
     * @param userId    当前登录用户ID
     * @param articleId 关联文章ID（null=纯新草稿维度）
     */
    private void cleanupOldSnapshotsAfterCommit(Long userId, Long articleId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 保留最近 keepVersions 版的快照ID，其余（更旧快照）物理删除（article_draft 无 del_flag）
                List<Long> keepIds = articleDraftMapper.selectList(dimensionWrapper(userId, articleId)
                                .orderByDesc(ArticleDraft::getDraftVersion)
                                .last("LIMIT " + keepVersions))
                        .stream().map(ArticleDraft::getId).toList();
                if (keepIds.isEmpty()) {
                    return;
                }
                int removed = articleDraftMapper.delete(dimensionWrapper(userId, articleId)
                        .notIn(ArticleDraft::getId, keepIds));
                if (removed > 0) {
                    log.info("草稿快照滚动清理完成：userId={}, articleId={}, 保留 {} 版, 清理 {} 行",
                            userId, articleId, keepIds.size(), removed);
                }
            }
        });
    }

    /**
     * 构建草稿快照维度查询条件（与 locateDraft 定位口径一致）：
     * articleId 传值→文章维度；null→本人未关联文章维度
     *
     * @param userId    当前登录用户ID
     * @param articleId 关联文章ID（可空）
     * @return 维度查询 wrapper（不含排序/limit，由调用方追加）
     */
    private LambdaQueryWrapper<ArticleDraft> dimensionWrapper(Long userId, Long articleId) {
        return new LambdaQueryWrapper<ArticleDraft>()
                .eq(articleId != null, ArticleDraft::getArticleId, articleId)
                .eq(articleId == null, ArticleDraft::getAuthorId, userId)
                .isNull(articleId == null, ArticleDraft::getArticleId);
    }

    /**
     * 我的草稿列表（按 author_id 归属过滤，仅返回卡片不含 content_html 大字段）
     *
     * @param userId 当前登录用户ID
     * @return 草稿卡片列表
     */
    @Override
    public List<DraftCardVO> listMy(Long userId) {
        // TODO 按 author_id 归属过滤
        return null;
    }

    /**
     * 草稿详情（含 content_html 供编辑器回显；归属校验防横向越权，非本人按不存在处理）
     *
     * @param id     草稿ID
     * @param userId 当前登录用户ID
     * @return 详情 VO（draftVersion 供前端作下次 baseVersion）
     */
    @Override
    public DraftDetailVO getDetail(Long id, Long userId) {
        // TODO 非本人按不存在处理
        return null;
    }

    /**
     * 删除草稿（仅本人；草稿图片 draft_id 归属清理，废弃孤儿图片由 file 对账链路兑底）
     *
     * @param id     草稿ID
     * @param userId 当前登录用户ID
     */
    @Override
    public void delete(Long id, Long userId) {
        // TODO 仅本人；草稿图片归属清理，废弃孤儿图片由 file 对账链路兜底
    }
}