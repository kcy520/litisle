package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 草稿卡片 VO（我的草稿列表；不带 content_html 大字段）
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class DraftCardVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 草稿ID */
    private Long id;

    /** 关联文章ID（null=纯新草稿） */
    private Long articleId;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 封面文件ID */
    private String coverFileId;

    /** 分类（@JsonValue 序列化输出中文分类名，前端无感） */
    private ArticleCategoryEnum category;

    /** 最新草稿版本号 */
    private Integer draftVersion;

    /** 最后保存时间（取 update_time） */
    private LocalDateTime updateTime;
}