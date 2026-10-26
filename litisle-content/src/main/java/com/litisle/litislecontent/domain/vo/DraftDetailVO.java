package com.litisle.litislecontent.domain.vo;

import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 草稿详情 VO（编辑器回显用，含 content_html；归属校验通过后才返回）
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class DraftDetailVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 草稿ID */
    private Long id;

    /** 关联文章ID（null=纯新草稿） */
    private Long articleId;

    /** 标题 */
    private String title;

    /** 摘要 */
    private String summary;

    /** 正文HTML（图片为 {img:file_id} 占位符） */
    private String contentHtml;

    /** 封面文件ID */
    private String coverFileId;

    /** 分类（@JsonValue 序列化输出中文分类名，前端无感） */
    private ArticleCategoryEnum category;

    /** 可见性（发布时继承） */
    private VisibilityEnum visibility;

    /** 当前草稿版本号（下次保存作 baseVersion） */
    private Integer draftVersion;

    /** 草稿配图列表 */
    private List<ArticleImageVO> images;

    /** 最后保存时间 */
    private LocalDateTime updateTime;
}