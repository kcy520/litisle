package com.litisle.litislecontent.domain.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislecontent.enums.ArticleCategoryEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * article_draft 实体（litisle_content 库草稿自动保存表，多次编辑历史）
 * <p>设计原因：草稿高频写、低频读，与 article 分离避免污染主表查询缓存；
 * 本表每个草稿（按 draft_id/article_id 维度）永远只有一行：保存即原地覆盖、只存最新内容（历史版本不留存），
 * draft_version 作多端并发编辑的乐观锁计数器；发布后 article_id 回填关联正式文章</p>
 */
@Data
@Accessors(chain = true)
@TableName("article_draft")
public class ArticleDraft implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 草稿ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联文章ID
     * <p>bigint 可空：新草稿为 null，发布后回填</p>
     */
    @TableField("article_id")
    private Long articleId;

    /**
     * 作者用户ID
     * <p>bigint，索引 idx_author_id</p>
     */
    @TableField("author_id")
    private Long authorId;

    /**
     * 标题
     * <p>varchar(100)</p>
     */
    @Size(max = 100, message = "标题长度不能超过 100 个字符")
    @TableField("title")
    private String title;

    /**
     * 摘要
     * <p>varchar(500)，可空</p>
     */
    @Size(max = 500, message = "摘要长度不能超过 500 个字符")
    @TableField("summary")
    private String summary;

    /**
     * 正文HTML
     * <p>longtext；图片只存 {img:file_id} 占位符</p>
     */
    @TableField("content_html")
    private String contentHtml;

    /**
     * 封面文件ID（SeaweedFS）
     * <p>varchar(64)，可空</p>
     */
    @Size(max = 64, message = "封面文件ID长度不能超过 64 个字符")
    @TableField("cover_file_id")
    private String coverFileId;

    /**
     * 分类（单选）
     * <p>varchar(20) 直存中文分类名，@EnumValue 映射，默认"散文"</p>
     */
    @TableField("category")
    private ArticleCategoryEnum category;

    /**
     * 可见性（与 article 一致，发布时继承）
     * <p>PUBLIC(1)-公开 / PRIVATE(2)-仅自己可见 / FOLLOWERS(3)-仅关注者可见；阶段9：密码可见 4 已下线</p>
     */
    @TableField("visibility")
    private VisibilityEnum visibility;

    /**
     * 草稿版本号（多端并发编辑的乐观锁计数器：内容变更时 +1，客户端 baseVersion 落后则拒绝保存）
     */
    @TableField("draft_version")
    private Integer draftVersion;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}