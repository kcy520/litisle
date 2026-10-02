package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * report_evidence 实体（举报证据关联表）
 * <p>复合主键 (report_id, file_id)：MyBatis-Plus 不支持复合主键，此处以 report_id 作
 * @TableId(type=INPUT) 占位，真实复合键由 Mapper 自定义 SQL 维护（BaseMapper 单键 CRUD 慎用）</p>
 * <p>file_id 引用 file_meta.file_id 业务主键（注意与 review_task.biz_id 引用的 file_meta.id 是两套引用）；
 * JOIN file_meta 校验证据归属与 safe_flag，管理端展示走 REVIEWER/ADMIN 鉴权预签名直读</p>
 * <p>MyBatis-Plus：@TableName("report_evidence")</p>
 */
@Data
@Accessors(chain = true)
@TableName("report_evidence")
public class ReportEvidence implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 举报ID（复合主键之一，占位 @TableId）
     */
    @TableId(value = "report_id", type = IdType.INPUT)
    private Long reportId;

    /**
     * 证据文件ID（引用 file_meta.file_id 业务主键，复合主键之一）
     */
    @Size(max = 64, message = "证据文件ID长度不能超过 64 个字符")
    @TableField("file_id")
    private String fileId;

    /**
     * 关联时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}