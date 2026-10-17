package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.AlignEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 正文配图 VO（详情/草稿回显，渲染时按 positionOrder 替换 {img:file_id} 占位符）
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class ArticleImageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SeaweedFS 文件ID */
    private String fileId;

    /** 正文中的顺序号 */
    private Integer positionOrder;

    /** 对齐方式（权威来源 article_image.align） */
    private AlignEnum align;

    /** 宽度（px） */
    private Integer width;

    /** 高度（px） */
    private Integer height;
}