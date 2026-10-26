package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;

/**
 * 文章发布入参（ArticleService#createAndPublish，同步段 A1→A3）
 * <p>A1 校验口径：标题非空 ≤100；正文与配图不可同时为空（阶段9：密码可见已下线）</p>
 * <p>authorId 不由前端传——统一 StpUtil.getLoginIdAsLong() 注入，防伪造他人身份</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ArticlePublishDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 标题（≤100） */
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题长度不能超过 100 个字符")
    private String title;

    /** 摘要（≤500，可空；为空时业务层可截取正文生成） */
    @Size(max = 500, message = "摘要长度不能超过 500 个字符")
    private String summary;

    /** 正文HTML（图片只存 {img:file_id} 占位符；与配图不可同时为空，A1 校验兜底） */
    private String contentHtml;

    /** 封面文件ID（SeaweedFS，可空） */
    @Size(max = 64, message = "封面文件ID长度不能超过 64 个字符")
    private String coverFileId;

    /** 分类（单选，默认"散文"；JSON 传中文分类名，枚举反序列化即白名单校验） */
    private ArticleCategoryEnum category;

    /** 分区（可空=默认 NORMAL-普通分区） */
    private ZoneEnum zone;

    /** 可见性（1-公开/2-仅自己/3-仅关注者；阶段9：密码可见 4 已下线） */
    @NotNull(message = "可见性不能为空")
    private VisibilityEnum visibility;

    /** 正文配图列表（positionOrder 对应正文占位符顺序；可空） */
    @Size(max = 50, message = "配图数量不能超过 50 张")
    private List<ArticleImageDTO> images;

    /** 来源草稿ID（可空锚点：非空时发布链路按此定位草稿做归属切换——删 draft_id 图行
     *  并 afterCommit unbind DRAFT_IMAGE 引用，消除草稿图行/引用双残留；归属校验由业务层执行） */
    @Positive(message = "草稿ID必须为正数")
    private Long draftId;

    /** 标签名列表（每篇 ≤5；池中无命中走 DFA 闸门受控新建） */
    @Size(max = 5, message = "标签数量不能超过 5 个")
    private List<String> tagNames;
}