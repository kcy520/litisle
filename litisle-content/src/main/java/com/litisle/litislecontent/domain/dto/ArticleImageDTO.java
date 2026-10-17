package com.litisle.litislecontent.domain.dto;

import com.litisle.litislecontent.enums.AlignEnum;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 正文配图项（发布/编辑入参的图片描述，落 article_image 表）
 * <p>归属切换：草稿期挂 draft_id，发布后业务层回填 article_id 并清空 draft_id</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ArticleImageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SeaweedFS 文件ID（上传接口先行返回） */
    @NotBlank(message = "文件ID不能为空")
    @Size(max = 64, message = "文件ID长度不能超过 64 个字符")
    private String fileId;

    /** 图片在正文中的顺序号（渲染时按此序替换 {img:file_id} 占位符） */
    @NotNull(message = "顺序号不能为空")
    @Min(value = 0, message = "顺序号不能为负数")
    private Integer positionOrder;

    /** 对齐方式（权威来源在本表；file 库 align 仅冗余） */
    private AlignEnum align;

    /** 宽度（px，可空） */
    private Integer width;

    /** 高度（px，可空） */
    private Integer height;
}