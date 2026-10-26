package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 文章建档入参（ArticleService#saveDraftRow，先建档攒批 → publishBatch 批量送审链路的上游）
 * <p>与 ArticlePublishDTO 的差异：visibility 可空（缺省 PRIVATE，草稿态行不对外）；
 * 不含 tagNames（标签挂载随板块三标签链路落地，见 createAndPublish 同款 TODO）</p>
 * <p>authorId 不由前端传——统一 StpUtil.getLoginIdAsLong() 注入，防伪造他人身份</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ArticleSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标题（≤100） */
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题长度不能超过 100 个字符")
    private String title;

    /** 摘要（≤500，可空） */
    @Size(max = 500, message = "摘要长度不能超过 500 个字符")
    private String summary;

    /** 正文HTML（图片只存 {img:file_id} 占位符；与配图不可同时为空，业务层 A1 校验兜底） */
    private String contentHtml;

    /** 封面文件ID（SeaweedFS，可空） */
    @Size(max = 64, message = "封面文件ID长度不能超过 64 个字符")
    private String coverFileId;

    /** 分类（单选，默认"散文"；枚举反序列化即白名单校验） */
    private ArticleCategoryEnum category;

    /** 分区（可空=默认 NORMAL-普通分区；zone=2 需 AUTHOR） */
    private ZoneEnum zone;

    /** 可见性（可空=默认 PRIVATE；建档时显式传 PUBLIC 的，过审发布后即对外可见） */
    private VisibilityEnum visibility;

    /** 正文配图列表（可空） */
    @Size(max = 50, message = "配图数量不能超过 50 张")
    private List<ArticleImageDTO> images;

    /** 来源草稿ID（可空锚点：非空时建档链路同款做草稿图行/封面引用归属切换） */
    @Positive(message = "草稿ID必须为正数")
    private Long draftId;
}
