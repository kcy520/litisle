package com.litisle.litislecontent.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.domain.dto.ArticleImageDTO;
import com.litisle.litislecontent.domain.dto.DraftSaveDTO;
import com.litisle.litislecontent.domain.entity.Article;
import com.litisle.litislecontent.domain.entity.ArticleDraft;
import com.litisle.litislecontent.domain.entity.ArticleImage;
import com.litisle.litislecontent.domain.vo.ArticleImageVO;
import com.litisle.litislecontent.domain.vo.DraftCardVO;
import com.litisle.litislecontent.domain.vo.DraftDetailVO;
import com.litisle.litislecontent.domain.vo.DraftSaveVO;
import com.litisle.litislecontent.mapper.ArticleDraftMapper;
import com.litisle.litislecontent.mapper.ArticleImageMapper;
import com.litisle.litislecontent.mapper.ArticleMapper;
import com.litisle.litislecontent.service.DraftService;
import com.litisle.litislecontent.service.support.ArticleImageRefSupport;
import com.litisle.litislecontent.service.support.ArticleImageRenderSupport;
import com.litisle.litislecontent.utils.ArticleHtmlSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 草稿域实现（单行 UPSERT/乐观锁，§6.2）。
 * <p>作用：负责草稿的保存、定位与删除——保存时靠"版本号比对"防止多端同时编辑互相覆盖；
 * 每个草稿（按 draftId/articleId 维度）永远只有一行，每次保存原地覆盖、draft_version+1，
 * 只存最新内容（历史版本不留存），防止正文大字段把表撑大。
 * 定位规则：draftId 传了→按草稿ID定位（支持多个纯草稿并存）；articleId 传了→找该文章的最新草稿快照；
 * 都没传→视为全新草稿新建（首存返回 draftId 给前端形成闭环，多端并发编辑靠版本号比对兜底）</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DraftServiceImpl implements DraftService {

    /** 文章 Mapper（编辑已有文章草稿链路的文章归属校验数据源） */
    private final ArticleMapper articleMapper;

    /** 草稿 Mapper（单行 UPSERT 保存/定位/删除数据源） */
    private final ArticleDraftMapper articleDraftMapper;

    /** 文章配图 Mapper（草稿图片暂存/幂等比对/整体重挂数据源） */
    private final ArticleImageMapper articleImageMapper;

    /** 文章/草稿插图引用支撑组件（阶段6：批量归属校验 + file_ref bind/unbind 统一收口，与 ArticleServiceImpl 共用） */
    private final ArticleImageRefSupport articleImageRefSupport;

    /** 正文配图占位符渲染支撑组件（草稿详情回显：fileId 批量换 URL + 占位符替换 <img>，与 ArticleServiceImpl 共用） */
    private final ArticleImageRenderSupport articleImageRenderSupport;

    /**
     * 草稿保存（重复请求不产生副作用，§6.2）：先定位草稿并校验归属 → 内容净化 →
     * 按版本号比对决定"拒绝/跳过/保存"（详见方法体内 ①~⑤ 流程注释）→
     * 单行 UPSERT 落库 → 传了图片列表就整体替换。
     *
     * @param dto    保存入参（articleId=null 纯新草稿；baseVersion 是版本比对的基准；images null=不修改图片）
     * @param userId 当前登录用户ID（归属校验基准）
     * @return 保存结果 {draftId, draftVersion}，前端以 draftVersion 作下次 baseVersion 形成闭环
     * @throws BizException ARTICLE_DRAFT_CONFLICT 版本落后 / ARTICLE_PARAM_INVALID 版本超前 / ARTICLE_NOT_FOUND 越权
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public DraftSaveVO save(DraftSaveDTO dto, Long userId) {

        /**
         * 草稿快照保存流程（幂等 + 乐观锁）：
         *
         * ① 定位草稿：传了 draftId 按草稿定位（多草稿并存），有关联文章按文章找最新快照，都没传视为全新草稿；
         *    同时校验归属（只能操作自己的草稿/文章）。
         * ② 内容净化：标题/摘要剥标签，正文白名单过滤（与正式文章同款防 XSS）。
         * ③ 版本判定（乐观锁，严格按序四分支）：
         *    - 客户端版本落后 → 拒绝（后写不覆盖先写，前端刷新重试）；
         *    - 客户端版本超前 → 拒绝（防御性，理论不可达）；
         *    - 版本一致且内容没变 → 幂等跳过（网络重发不递增版本）；
         *    - 版本一致且有变更 → 保存并递增版本号。
         * ④ 落库：新建或更新快照；封面变更时校验归属并交接引用
         *    （新封面先绑定，旧封面提交后解绑）。
         * ⑤ 配图整体重挂：传了图列表就全量替换草稿图。
         */

        // 步骤1：定位草稿 + 归属校验（draft/article 的 author_id 比对当前登录用户，防横向越权）
        ArticleDraft draft = locateDraft(dto, userId);
        // 步骤2：入库前先做内容净化（与正式文章同款处理，防止恶意脚本借草稿链路存进数据库）
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
        // 阶段8② 先记下新旧封面ID（必须在下方 setCoverFileId 覆盖之前采集；MyBatis-Plus 更新时传 null 表示沿用旧封面）
        String oldCoverId = draft == null ? null : draft.getCoverFileId();
        String newCoverId = dto.getCoverFileId() == null ? oldCoverId : dto.getCoverFileId();
        // 封面换了才校验归属（有问题立刻报错，防止把别人的文件挂成自己的封面；沿用旧封面/无封面不发远程调用）
        if (newCoverId != null && !newCoverId.equals(oldCoverId)) {
            articleImageRefSupport.checkCoverOwnership(newCoverId, userId);
        }
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
                // 分类枚举直接赋值；传 null 的含义：新增时数据库落默认值'散文'，
                // 更新时沿用草稿已有分类（这里不能改成统一落默认值——自动保存通常不带分类，会把用户选的分类误覆盖掉）
                .setCategory(dto.getCategory())
                // visibility 传 null=沿用草稿已有值，避免自动保存把可见性误清空
                .setVisibility(dto.getVisibility() == null ? draft.getVisibility() : dto.getVisibility());
        if (draft.getId() == null) {
            articleDraftMapper.insert(draft);
        } else {
            articleDraftMapper.updateById(draft);
        }
        // 阶段8② 封面引用登记/交接（与正文配图同链路；只有换了封面才动引用，沿用同封面/无封面都不动）：
        if (newCoverId != null && !newCoverId.equals(oldCoverId)) {
            // 新封面在同一个事务里登记引用（登记失败整个保存一起回滚，不会出现"封面挂上了但引用没登记"的中间状态）
            articleImageRefSupport.bindSingleRef(newCoverId,
                    ContentBizConstant.FILE_BIZ_TYPE_COVER, draft.getId(), userId);
            // 旧封面等事务提交成功后再解绑（按 业务类型+草稿ID+用户 精确匹配；解绑失败只记 warn 日志，由对账任务兜底）
            if (oldCoverId != null) {
                articleImageRefSupport.unbindRefsAfterCommit(List.of(oldCoverId),
                        ContentBizConstant.FILE_BIZ_TYPE_COVER, draft.getId(), userId);
            }
        }
        // 步骤4：images 非 null=整体替换草稿配图（草稿期图片只挂 draft_id，article_id 留空；
        // 发布时（阶段6）由 ArticleServiceImpl#switchDraftImageOwnership 负责把图片划归文章：
        // 删 draft_id 图行并解绑草稿引用，草稿快照行本身保留只存文本）
        if (dto.getImages() != null) {
            remountDraftImages(dto.getImages(), draft.getId(), userId);
        }
        // 返回保存结果：draftVersion 供前端作下次 baseVersion 形成闭环
        return new DraftSaveVO().setDraftId(draft.getId()).setDraftVersion(nextVersion);
    }

    /**
     * 定位草稿快照 + 归属校验（防横向越权）
     * <p>draftId 传值→按草稿ID定位（支持多草稿并存）；articleId 传值→校验文章归属后按文章定位最新快照；
     * 都未传→全新草稿返回 null（由调用方新建）</p>
     *
     * @param dto    保存入参
     * @param userId 当前登录用户ID
     * @return 最新草稿快照（无任何草稿时返回 null，由调用方走新建分支）
     */
    private ArticleDraft locateDraft(DraftSaveDTO dto, Long userId) {
        // 链路1：编辑指定草稿（前端回传 draftId）——按行定位，支持多个纯草稿并存，各编辑各行互不覆盖
        if (dto.getDraftId() != null) {
            ArticleDraft draft = articleDraftMapper.selectById(dto.getDraftId());
            // 归属校验：非本人按不存在处理，不暴露存在性
            if (draft == null || !userId.equals(draft.getAuthorId())) {
                throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
            }
            return draft;
        }
        // 链路2：编辑已有文章的草稿——先校验文章归属，防越权读写他人文章草稿（非本人按不存在处理）
        if (dto.getArticleId() != null) {
            Article article = articleMapper.selectById(dto.getArticleId());
            if (article == null || !Objects.equals(article.getAuthorId(), userId)) {
                throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
            }
            // 用 Page 替代 .last("LIMIT 1")：分页插件标准方式，无裸 SQL 字符串（searchCount=false 不触发 COUNT）
            List<ArticleDraft> drafts = articleDraftMapper.selectList(Page.of(1, 1, false),
                    new LambdaQueryWrapper<ArticleDraft>()
                            .eq(ArticleDraft::getArticleId, dto.getArticleId())
                            .orderByDesc(ArticleDraft::getDraftVersion));
            // 取最新一条即目标快照（无快照时保持 null，由调用方新建）
            ArticleDraft draft = drafts.isEmpty() ? null : drafts.get(0);
            // 草稿快照归属兜底校验（正常与文章归属一致，防御性比对）
            if (draft != null && !Objects.equals(draft.getAuthorId(), userId)) {
                throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
            }
            return draft;
        }
        // 链路3：全新草稿（draftId/articleId 均未传）——不再复用旧纯草稿（多草稿并存下复用会互相覆盖），
        // 返回 null 由调用方走新建分支，首存响应返回 draftId 形成闭环
        return null;
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
     * 草稿图片整体重挂（暂存）：先删该草稿旧图再按入参顺序插入新图（约定：images 非 null=整体替换）
     * <p>草稿期图片只挂 draft_id、article_id 留空；发布时（阶段6）由
     * ArticleServiceImpl#switchDraftImageOwnership 负责划归文章（删 draft_id 图行 + 解绑草稿引用）；
     * 图片引用由本模块显式管理：保存时在同一事务里登记引用、事务提交后解绑被移除图片的引用，
     * file 侧对账只对已解绑文件做 7 天缓冲后清理兜底</p>
     *
     * @param images  新配图列表（DTO 校验 ≤50 张）
     * @param draftId 草稿快照ID（图片挂载锚点，单行 UPSERT 语义下恒定）
     * @param userId  当前登录用户ID
     */
    private void remountDraftImages(List<ArticleImageDTO> images, Long draftId, Long userId) {
        // 归属校验：每个 fileId 必须属于本人且是文章插图类型（与 ArticleServiceImpl 同规则，防止引用他人文件/违规图）
        checkImageOwnership(images, userId);
        // 阶段6：删之前先记下旧挂图的 fileId（后面要靠它算出"哪些图被移除了"，删完就查不到了）
        List<String> oldFileIds = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                        .eq(ArticleImage::getDraftId, draftId))
                .stream().map(ArticleImage::getFileId).collect(Collectors.toList());
        // 整体重挂：先物理删除旧图行（article_image 表没有逻辑删除标记）再按入参顺序插入
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
        // 阶段6：在同一事务里逐张登记图片引用（重复登记无副作用；登记失败保存整体回滚，
        // 不会出现"图挂上了但引用没登记"的中间状态）——草稿期就登记引用，
        // 防止秒传共享的文件在草稿占用期间被对账任务误清
        articleImageRefSupport.bindDraftRefs(images, draftId, userId);
        // 阶段6：事务提交后，对"旧列表减新列表"得到的被移除图片解绑草稿引用（按 业务类型+草稿ID+用户 精确匹配；
        // 失败只记 warn 日志不中断：残留引用最多晚点删/不删，宁可多留也不误删）
        List<String> removedFileIds = articleImageRefSupport.diffOldMinusNew(oldFileIds,
                images.stream().map(ArticleImageDTO::getFileId).collect(Collectors.toList()));
        articleImageRefSupport.unbindRefsAfterCommit(removedFileIds,
                ContentBizConstant.FILE_BIZ_TYPE_DRAFT_IMG, draftId, userId);
    }

    /**
     * 插图归属校验（阶段6 优化：把逐张远程查询合并成一次）：委托 ArticleImageRefSupport 批量校验
     * （每个 fileId 必须属于本人且是文章插图类型，发现问题立刻报错，与 ArticleServiceImpl 同规则）；
     * 原来逐图调 Feign getFileInfo（最多 50 次远程调用）改为 batchGetOwners 一次往返
     *
     * @param images   待校验配图列表
     * @param authorId 当前登录用户ID
     */
    private void checkImageOwnership(List<ArticleImageDTO> images, Long authorId) {
        articleImageRefSupport.checkOwnership(images, authorId);
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
     * 我的草稿列表（按 author_id 归属过滤，仅返回卡片不含 content_html 大字段）
     *
     * @param userId 当前登录用户ID
     * @return 草稿卡片列表
     */
    @Override
    public List<DraftCardVO> listMy(Long userId) {
        // 步骤1：按 author_id 归属过滤，显式投影排除 content_html 大字段（列表卡片用不到，防 longtext 拖慢查询）
        List<ArticleDraft> drafts = articleDraftMapper.selectList(new LambdaQueryWrapper<ArticleDraft>()
                .eq(ArticleDraft::getAuthorId, userId)
                .select(ArticleDraft::getId, ArticleDraft::getArticleId, ArticleDraft::getTitle,
                        ArticleDraft::getSummary, ArticleDraft::getCoverFileId, ArticleDraft::getCategory,
                        ArticleDraft::getDraftVersion, ArticleDraft::getUpdateTime)
                .orderByDesc(ArticleDraft::getUpdateTime)
                .orderByDesc(ArticleDraft::getId));
        // 步骤2：实体映射为草稿卡片 VO（不含正文，仅列表展示字段）
        return drafts.stream()
                .map(draft -> new DraftCardVO()
                        .setId(draft.getId())
                        .setArticleId(draft.getArticleId())
                        .setTitle(draft.getTitle())
                        .setSummary(draft.getSummary())
                        .setCoverFileId(draft.getCoverFileId())
                        .setCategory(draft.getCategory())
                        .setDraftVersion(draft.getDraftVersion())
                        .setUpdateTime(draft.getUpdateTime()))
                .collect(Collectors.toList());
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
        // 步骤1：查询草稿并校验归属（非本人按不存在处理，不暴露存在性，与 delete 口径一致）
        ArticleDraft draft = articleDraftMapper.selectById(id);
        if (draft == null || !userId.equals(draft.getAuthorId())) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // 步骤2：查询草稿配图（按 positionOrder 升序，渲染时按顺序替换 {img:file_id} 占位符）
        List<ArticleImage> imageRows = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getDraftId, id)
                .orderByAsc(ArticleImage::getPositionOrder));
        // 步骤3：正文占位符渲染（{img:file_id} → 带 URL 的 <img>，与文章详情同款；
        // 查不到记录/URL 的占位符原样保留降级，不破坏正文结构）
        String contentHtml = draft.getContentHtml();
        if (contentHtml != null && !contentHtml.isEmpty() && !imageRows.isEmpty()) {
            contentHtml = articleImageRenderSupport.renderImages(contentHtml, imageRows);
        }
        // 步骤4：配图实体 → VO（编辑器图片属性面板/回显辅助）
        List<ArticleImageVO> images = imageRows.stream()
                .map(img -> new ArticleImageVO()
                        .setFileId(img.getFileId())
                        .setPositionOrder(img.getPositionOrder())
                        .setAlign(img.getAlign())
                        .setWidth(img.getWidth())
                        .setHeight(img.getHeight()))
                .collect(Collectors.toList());
        // 步骤5：组装详情 VO（渲染后正文供编辑器回显；draftVersion 供前端作下次 baseVersion）
        return new DraftDetailVO()
                .setId(draft.getId())
                .setArticleId(draft.getArticleId())
                .setTitle(draft.getTitle())
                .setSummary(draft.getSummary())
                .setContentHtml(contentHtml)
                .setCoverFileId(draft.getCoverFileId())
                .setCategory(draft.getCategory())
                .setVisibility(draft.getVisibility())
                .setDraftVersion(draft.getDraftVersion())
                .setImages(images)
                .setUpdateTime(draft.getUpdateTime());
    }

    /**
     * 删除草稿（仅本人，阶段6 落地）：归属校验 → 删草稿行 → 删草稿图行 →
     * 事务提交后解绑全部配图引用和封面引用（阶段8②；解绑失败只记 warn 日志补偿，
     * 真正废弃的孤儿图片仍由 file 侧对账兜底清理）
     *
     * @param id     草稿ID
     * @param userId 当前登录用户ID
     * @throws BizException ARTICLE_NOT_FOUND 草稿不存在或非本人（不暴露存在性）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId) {

        /**
         * 删除草稿流程：
         *
         * ① 归属校验：仅本人可删（非本人按不存在处理，不暴露存在性）。
         * ② 物理删除草稿行。
         * ③ 删除草稿配图行（删前先记录 fileId 作为解绑基准）。
         * ④ 事务提交后解绑配图引用和封面引用
         *    （防残留引用锁死秒传共享文件；失败仅留痕，由对账兑底）。
         */

        // 归属校验：仅本人可删（非本人按不存在处理，不暴露存在性）
        ArticleDraft draft = articleDraftMapper.selectById(id);
        if (draft == null || !userId.equals(draft.getAuthorId())) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        // 删草稿行（article_draft 表没有逻辑删除标记，直接物理删）
        articleDraftMapper.deleteById(id);
        // 删前查全量草稿图行（解绑基准）
        List<String> fileIds = articleImageMapper.selectList(new LambdaQueryWrapper<ArticleImage>()
                        .eq(ArticleImage::getDraftId, id))
                .stream().map(ArticleImage::getFileId).collect(Collectors.toList());
        // 删草稿图行（物理删，article_image 表没有逻辑删除标记）
        articleImageMapper.delete(new LambdaQueryWrapper<ArticleImage>()
                .eq(ArticleImage::getDraftId, id));
        // 阶段6：事务提交后解绑全部配图引用（防止残留引用把秒传共享文件"锁死"删不掉；失败只记 warn 日志，由对账兜底）
        articleImageRefSupport.unbindRefsAfterCommit(fileIds,
                ContentBizConstant.FILE_BIZ_TYPE_DRAFT_IMG, id, userId);
        // 阶段8②：草稿封面解绑（与正文配图同链路；失败只记 warn 日志，由对账兜底）
        if (draft.getCoverFileId() != null) {
            articleImageRefSupport.unbindRefsAfterCommit(List.of(draft.getCoverFileId()),
                    ContentBizConstant.FILE_BIZ_TYPE_COVER, id, userId);
        }
    }
}