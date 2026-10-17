package com.litisle.litislecontent.domain.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislecontent.enums.AlignEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * article_image 实体（litisle_content 库文章图片表，记录插入位置与对齐方式）
 * <p>设计原因：图片可在正文任意位置插入 + 5 种对齐，落表后支持"图片管理"与重新排版</p>
 * <p>idx_file_id 支撑图片复审再聚合：按 file_id 反查引用该图的所有文章，
 * 定位 ARTICLE_IMG_RE_REVIEW 复审任务重算聚合结论，防挂起任务永久卡待审（见 Review-Plan §4.2.2④）</p>
 */
@Data
@Accessors(chain = true)
@TableName("article_image")
public class ArticleImage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文章ID
     * <p>bigint 可空：草稿期新草稿插图仅 draft_id 归属，发布归属切换时回填；与 draft_id 二选一</p>
     */
    @TableField("article_id")
    private Long articleId;

    /**
     * 草稿ID
     * <p>bigint 可空：与 article_id 二选一，发布后清空</p>
     */
    @TableField("draft_id")
    private Long draftId;

    /**
     * SeaweedFS 文件ID
     * <p>varchar(64)，索引 idx_file_id 支撑图片复审按图反查引用文章</p>
     */
    @Size(max = 64, message = "文件ID长度不能超过 64 个字符")
    @TableField("file_id")
    private String fileId;

    /**
     * 图片在正文中的顺序号
     * <p>int，渲染时按 position_order 替换正文占位符</p>
     */
    @TableField("position_order")
    private Integer positionOrder;

    /**
     * 对齐方式（权威来源）
     * <p>LEFT(1)/LEFT_CENTER(2)/CENTER(3)/RIGHT_CENTER(4)/RIGHT(5)；file 库 align 仅作冗余</p>
     */
    @TableField("align")
    private AlignEnum align;

    /**
     * 宽度（px）
     */
    @TableField("width")
    private Integer width;

    /**
     * 高度（px）
     */
    @TableField("height")
    private Integer height;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}