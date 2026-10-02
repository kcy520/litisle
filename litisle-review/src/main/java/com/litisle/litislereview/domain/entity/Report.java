package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.AbuseFlagEnum;
import com.litisle.litislereview.enums.ReportStatusEnum;
import com.litisle.litislereview.enums.TargetTypeEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * report 实体（举报表）
 * <p>防刷：active_key 生成列唯一索引兜底并发重复，应用层保留友好提示——同一
 * reporter_id+target_type+target_id 存在 status<3 的举报即拒绝</p>
 * <p>禁止 reporter_id 等于目标 owner（防自导自演）；撤回仅举报人本人、仅 status<3</p>
 * <p>MyBatis-Plus：@TableName("report") + @TableId(value="id", type=AUTO)</p>
 */
@Data
@Accessors(chain = true)
@TableName("report")
public class Report implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 举报ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 举报人ID
     */
    @TableField("reporter_id")
    private Long reporterId;

    /**
     * 目标类型：1-文章 2-评论 3-用户 4-私信 5-图片/文件（target_id = file_meta.id）
     * <p>与 review_task.biz_type 错位（私信 5↔4、图片 6↔5），映射见 Review-Plan §4.5.2</p>
     */
    @TableField("target_type")
    private TargetTypeEnum targetType;

    /**
     * 目标ID
     */
    @TableField("target_id")
    private Long targetId;

    /**
     * 举报理由（UGC，回显须 HTML sanitize + 输出编码）
     */
    @Size(max = 200, message = "举报理由长度不能超过 200 个字符")
    @TableField("reason")
    private String reason;

    /**
     * 状态：0-待处理 1-初审中 2-转人工 3-已裁决 4-已撤回
     */
    @TableField("status")
    private ReportStatusEnum status;

    /**
     * 关联审核任务ID（转人工建 review_task 时回填；人审 decide 按此定位并回写 status=3）
     */
    @TableField("review_task_id")
    private Long reviewTaskId;

    /**
     * 处理人ID
     */
    @TableField("handler")
    private Long handler;

    /**
     * 裁决结果（UGC，回显须转义）
     */
    @Size(max = 200, message = "裁决结果长度不能超过 200 个字符")
    @TableField("result")
    private String result;

    /**
     * 诬告标记：0-否 1-是（裁决时判定「明确无依据/明显虚假」才置 1）
     */
    @TableField("abuse_flag")
    private AbuseFlagEnum abuseFlag;

    /**
     * 举报时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /**
     * 活跃举报去重键（DB 生成列 stored）
     * <p>if(status<3, concat(reporter_id,'-',target_type,'-',target_id), null)；status≥3 置空允许再举报</p>
     * <p>生成列不可由应用写入：insert/update 均置 NEVER</p>
     */
    @Size(max = 64, message = "活跃去重键长度不能超过 64 个字符")
    @TableField(value = "active_key", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeKey;
}