package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.AuthorApplyStatusEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * author_apply 实体（作家申请表）
 * <p>独立于 sys_user：申请记录需保留历史，多次申请形成流水</p>
 * <p>MyBatis-Plus：@TableName("author_apply") + @TableId(value="id", type=AUTO)</p>
 */
@Data
@Accessors(chain = true)
@TableName("author_apply")
public class AuthorApply implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 申请ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 申请人ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 笔名
     */
    @Size(max = 32, message = "笔名长度不能超过 32 个字符")
    @TableField("pen_name")
    private String penName;

    /**
     * 创作方向
     */
    @Size(max = 100, message = "创作方向长度不能超过 100 个字符")
    @TableField("creation_dir")
    private String creationDir;

    /**
     * 代表作简介
     */
    @Size(max = 500, message = "代表作简介长度不能超过 500 个字符")
    @TableField("masterpiece")
    private String masterpiece;

    /**
     * 状态：0-待审 1-通过 2-驳回
     */
    @TableField("status")
    private AuthorApplyStatusEnum status;

    /**
     * 审核员ID
     */
    @TableField("reviewer_id")
    private Long reviewerId;

    /**
     * 驳回原因（UGC，回显须转义）
     */
    @Size(max = 200, message = "驳回原因长度不能超过 200 个字符")
    @TableField("reject_reason")
    private String rejectReason;

    /**
     * 申请时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}