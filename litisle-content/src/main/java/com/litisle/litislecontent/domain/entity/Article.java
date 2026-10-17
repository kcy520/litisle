package com.litisle.litislecontent.domain.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.commoncore.enums.DelFlag;

import com.litisle.litislecontent.enums.ArticleStatusEnum;
import com.litisle.litislecontent.enums.VisibilityEnum;
import com.litisle.litislecontent.enums.ZoneEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * article 实体（litisle_content 库文章主表）
 * <p>MyBatis-Plus：@TableName("article") + @TableId(value="id", type=AUTO)</p>
 * <p>枚举字段靠 yml default-enum-type-handler 自动映射</p>
 */
@Data
@Accessors(chain = true)
@TableName("article")
public class Article implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文章ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 作者用户ID
     * <p>bigint，索引 idx_author_id</p>
     */
    @TableField("author_id")
    private Long authorId;

    /**
     * 分区
     * <p>NORMAL(1)-普通分区 / WRITER(2)-作家分区 / RECOMMEND(3)-推荐流（运营/算法推荐内容）</p>
     */
    @TableField("zone")
    private ZoneEnum zone;

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
     * <p>longtext，支持大篇幅切片前的完整正文；图片只存 {img:file_id} 占位符</p>
     */
    @TableField("content_html")
    private String contentHtml;

    /**
     * 封面文件ID（SeaweedFS）
     * <p>varchar(64)，可空；业务层只持 fileId，与存储后端解耦</p>
     */
    @Size(max = 64, message = "封面文件ID长度不能超过 64 个字符")
    @TableField("cover_file_id")
    private String coverFileId;

    /**
     * 分类（单选）
     * <p>varchar(20)：小说/散文/现代诗/随笔等，默认"散文"</p>
     */
    @Size(max = 20, message = "分类长度不能超过 20 个字符")
    @TableField("category")
    private String category;

    /**
     * 状态
     * <p>DRAFT(0)-草稿 / PENDING_REVIEW(1)-待审核 / PUBLISHED(2)-已发布 / REJECTED(3)-驳回 / TAKEN_DOWN(4)-下架</p>
     */
    @TableField("status")
    private ArticleStatusEnum status;

    /**
     * 可见性
     * <p>PUBLIC(1)-公开 / PRIVATE(2)-仅自己可见 / FOLLOWERS(3)-仅关注者可见 / PASSWORD(4)-密码访问</p>
     * <p>PASSWORD(4) 时密码存 access_password；发布时可选是否公开，后续可多选修改公开状态（批量发布）</p>
     */
    @TableField("visibility")
    private VisibilityEnum visibility;

    /**
     * 访问密码（visibility=4 时必填）
     * <p>varchar(128)，BCrypt 加密存储，与用户密码同级别保护</p>
     */
    @Size(max = 128, message = "访问密码长度不能超过 128 个字符")
    @TableField("access_password")
    private String accessPassword;

    /**
     * 批量发布批次ID
     * <p>bigint 可空；同批次发布的多篇文章/章节共享，便于按批次展示与批量操作</p>
     */
    @TableField("publish_batch_id")
    private Long publishBatchId;

    /**
     * 浏览量
     * <p>冗余计数，配合 Redis 异步刷新，避免列表查询 join 聚合</p>
     */
    @TableField("view_cnt")
    private Long viewCnt;

    /**
     * 点赞数
     * <p>冗余计数，配合 Redis 异步刷新</p>
     */
    @TableField("like_cnt")
    private Long likeCnt;

    /**
     * 评论数
     * <p>冗余计数，配合 Redis 异步刷新</p>
     */
    @TableField("comment_cnt")
    private Long commentCnt;

    /**
     * 内容指纹 SHA256
     * <p>char(64)，文本=标题+正文，对齐 review §2.1</p>
     * <p>配图聚合指纹属 ARTICLE_IMG_RE_REVIEW 场景不并入本列；免审直落/转公开不重审校验键</p>
     */
    @Size(max = 64, message = "内容指纹长度不能超过 64 个字符")
    @TableField("content_hash")
    private String contentHash;

    /**
     * 删除标志
     * <p>NOT_DELETED(0)-未删除 / DELETED(1)-已删除</p>
     */
    @TableField("del_flag")
    private DelFlag delFlag;

    /**
     * 乐观锁版本号
     */
    @TableField("version")
    private Integer version;

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