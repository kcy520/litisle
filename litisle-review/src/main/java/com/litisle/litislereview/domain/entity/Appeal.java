package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.AppealStatusEnum;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * appeal 实体（申诉表）
 * <p>report_id/punish_id/review_task_id 三者至少其一非空（应用层校验）；机审驳回场景无 report/punish，
 * 靠 review_task_id 定位被驳回内容并回写业务状态</p>
 * <p>防重双层：关联列级（同一 review_task_id/report_id/punish_id 存在任意状态申诉即拒绝）+
 * 内容级（同内容 biz_type+biz_id 存在 status=0 待裁决即拒绝），active_key 唯一索引兜底并发</p>
 * <p>MyBatis-Plus：@TableName("appeal") + @TableId(value="id", type=AUTO)</p>
 */
@Data
@Accessors(chain = true)
@TableName("appeal")
public class Appeal implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 申诉ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联举报ID
     */
    @TableField("report_id")
    private Long reportId;

    /**
     * 关联处罚记录ID（punish_log.id）
     */
    @TableField("punish_id")
    private Long punishId;

    /**
     * 关联审核任务ID（机审驳回申诉：无 report/punish 可关联，靠此定位被驳回内容）
     */
    @TableField("review_task_id")
    private Long reviewTaskId;

    /**
     * 申诉人ID（须与内容 owner 一致，防替他人申诉）
     */
    @TableField("appellant_id")
    private Long appellantId;

    /**
     * 申诉内容（UGC，回显须 HTML sanitize + 输出编码）
     */
    @Size(max = 500, message = "申诉内容长度不能超过 500 个字符")
    @TableField("content")
    private String content;

    /**
     * 状态：0-待裁决 1-维持 2-撤销
     */
    @TableField("status")
    private AppealStatusEnum status;

    /**
     * 裁决审核员ID（仅 ADMIN 可担任，原驳回裁决人回避）
     */
    @TableField("judge_by")
    private Long judgeBy;

    /**
     * 裁决意见（UGC，回显须转义）
     */
    @Size(max = 500, message = "裁决意见长度不能超过 500 个字符")
    @TableField("judge_opinion")
    private String judgeOpinion;

    /**
     * 裁决落库时间（judge 写入；内容级轮次新鲜度判定权威时间源）
     */
    @TableField("decide_time")
    private LocalDateTime decideTime;

    /**
     * 申诉目标内容业务类型（提交时由关联列解析落库；三者交叉须内容身份一致，冲突拒收）
     */
    @TableField("biz_type")
    private ModerationBizTypeEnum bizType;

    /**
     * 申诉目标内容业务ID（同 biz_type 语义）
     */
    @TableField("biz_id")
    private Long bizId;

    /**
     * 待裁决内容级去重键（DB 生成列 stored）
     * <p>if(status=0, concat(biz_type,'-',biz_id), null)；裁决后置空，不阻断新一轮驳回后的新申诉</p>
     * <p>生成列不可由应用写入：insert/update 均置 NEVER</p>
     */
    @Size(max = 64, message = "内容去重键长度不能超过 64 个字符")
    @TableField(value = "active_key", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activeKey;

    /**
     * 申诉时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}