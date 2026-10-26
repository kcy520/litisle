package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 草稿保存入参（DraftService#save，快照幂等写入）
 * <p>baseVersion 与服务端最新 draft_version 比对：相等→UPSERT 递增；落后→ARTICLE_DRAFT_CONFLICT 刷新；
 * 同版本重复提交→幂等跳过（多端/重试安全）</p>
 * <p>自动保存 60s 窗口限流（ContentLimitConstant.DRAFT_SAVE_LIMIT），超保留版数滚动清理最旧快照</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class DraftSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 草稿ID（编辑已有纯草稿时回传，定位具体草稿行支持多草稿并存；新建为 null） */
    private Long draftId;

    /** 关联文章ID（null=纯新草稿；已有草稿传 article_id 定位归属；与 draftId 同时传时以 draftId 为准） */
    private Long articleId;

    /** 标题（≤100） */
    @Size(max = 100, message = "标题长度不能超过 100 个字符")
    private String title;

    /** 摘要（≤500，可空） */
    @Size(max = 500, message = "摘要长度不能超过 500 个字符")
    private String summary;

    /** 正文HTML（图片只存 {img:file_id} 占位符） */
    private String contentHtml;

    /** 封面文件ID（可空） */
    @Size(max = 64, message = "封面文件ID长度不能超过 64 个字符")
    private String coverFileId;

    /** 分类（默认"散文"；枚举反序列化即白名单校验，可空） */
    private ArticleCategoryEnum category;

    /** 可见性（与 article 一致，发布时继承；可空=沿用草稿已有值） */
    private VisibilityEnum visibility;

    /** 乐观锁基准版本（客户端上次拿到的 draft_version；新草稿传 0） */
    @NotNull(message = "baseVersion 不能为空")
    private Integer baseVersion;

    /** 草稿配图（draft_id 归属；null=不修改，非 null=整体重挂） */
    @Size(max = 50, message = "配图数量不能超过 50 张")
    private List<ArticleImageDTO> images;
}