package com.litisle.litislecontent.service.support;

import com.litisle.litislecontent.domain.dto.ArticlePublishDTO;
import com.litisle.litislecontent.domain.entity.ArticleDraft;
import com.litisle.litislecontent.mapper.ArticleDraftMapper;
import com.litisle.litislecontent.utils.ArticleHtmlSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布入口草稿快照支撑组件（P1 发布异常保稿，方案B：发布前置草稿 UPSERT）。
 *
 * 作用：
 * 发布链路第一步（所有校验之前）把发布请求内容 UPSERT 进 article_draft 单行表——
 * 后续任何一步失败（图文校验/图片归属/敏感词/CAS 并发等），本次编辑内容必已在草稿行，用户不丢稿。
 * 与前端 10s 节流自动保存互为兜底（不依赖前端行为）；与现有 article_draft 单行 UPSERT
 * 设计完全同构（每草稿仅一行、原地覆盖），零新表。
 *
 * 【独立事务】REQUIRES_NEW：主发布链路校验失败回滚时，已提交的快照行不被连带回滚（保稿关键）；
 * 【失败不阻断】调用方 fail-safe 包裹（try-catch 仅 warn），快照异常不影响发布主链路
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishDraftSnapshotSupport {

    /** 草稿 Mapper（单行 UPSERT 数据源，与 DraftServiceImpl 共用） */
    private final ArticleDraftMapper articleDraftMapper;

    /**
     * 发布前草稿快照（UPSERT，重复调用无副作用）：定位草稿 → 内容净化 → 覆盖/新建草稿行。
     *
     * 定位规则（对齐 DraftServiceImpl.locateDraft 口径）：
     * draftId 非空 → 按行定位（存在且归属本人才覆盖；不存在/非本人则新建独立兜底行，防越权改写他人草稿）；
     * draftId 为空 → 新建兜底行（发布请求未关联草稿的直发场景）。
     *
     * 【不搬运图片行/引用】正文占位符 {img:file_id} 已含 fileId 锚点；发布失败时主链路
     * 尚未执行图片归属切换（switchDraftImageOwnership 在校验之后），原草稿图行/引用未被触碰，
     * 快照无需重挂图片，也避免新建兜底行的图行/file_ref 引用残留与清理联动
     *
     * @param dto      发布入参（快照数据源：标题/摘要/正文/封面/分类/可见性）
     * @param authorId 作者用户ID（草稿归属基准）
     * @return 本次快照实际写入的草稿行ID（覆盖行=原行ID；新建兜底行=新行ID），
     *         供发布主链路在文章建档成功后回填 article_id（草稿与已发布文章建立关联标识）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Long snapshotForPublish(ArticlePublishDTO dto, Long authorId) {
        // 步骤1：定位既有草稿行（draftId 非空时；非本人/已被删则视为无草稿，走新建兜底行）
        ArticleDraft draft = null;
        if (dto.getDraftId() != null) {
            draft = articleDraftMapper.selectById(dto.getDraftId());
            if (draft != null && !authorId.equals(draft.getAuthorId())) {
                // 非本人草稿不覆盖（防越权改写他人草稿），本次快照另建独立兜底行
                draft = null;
            }
        }
        // 步骤2：内容净化（与 DraftServiceImpl.save 同款：标题/摘要剥标签、正文白名单过滤，保证草稿行存安全内容）
        String title = ArticleHtmlSanitizer.stripTags(dto.getTitle());
        String summary = ArticleHtmlSanitizer.stripTags(dto.getSummary());
        String contentHtml = dto.getContentHtml() == null || dto.getContentHtml().isEmpty()
                ? dto.getContentHtml()
                : ArticleHtmlSanitizer.sanitize(dto.getContentHtml());
        // 步骤3：版本号沿用乐观锁计数器口径（原版本+1；新建行从 1 起）
        int nextVersion = (draft == null ? 0 : draft.getDraftVersion()) + 1;
        if (draft == null) {
            // 新建兜底行：article_id 留空（发布时文章尚未建档；发布成功后草稿行保留为快照，现状口径不变）
            draft = new ArticleDraft()
                    .setAuthorId(authorId)
                    .setDraftVersion(nextVersion);
        } else {
            draft.setDraftVersion(nextVersion);
        }
        
        // 步骤4：覆盖快照字段（null 字段语义与 DraftServiceImpl.save 一致：
        // 新增时由 DB 落默认值；更新时被 MP not_null 策略跳过=沿用草稿旧值，不误清已有分类/可见性）
        draft.setTitle(title)
                .setSummary(summary)
                .setContentHtml(contentHtml)
                .setCoverFileId(dto.getCoverFileId())
                .setCategory(dto.getCategory())
                .setVisibility(dto.getVisibility());
        // 步骤5：单行 UPSERT 落库（无行则插入、有行则原地覆盖）
        if (draft.getId() == null) {
            articleDraftMapper.insert(draft);
        } else {
            articleDraftMapper.updateById(draft);
        }
        // 步骤6：返回实际写入的草稿行ID（发布成功后由主链路回填 article_id，草稿行保留带已发布标识）
        return draft.getId();
    }
}
