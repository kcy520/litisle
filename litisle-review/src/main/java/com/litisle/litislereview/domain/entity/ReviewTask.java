package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.BizTypeEnum;
import com.litisle.litislereview.enums.Layer1HitEnum;
import com.litisle.litislereview.enums.Layer2RiskEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;
import com.litisle.litislereview.enums.ReviewTaskStatusEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * review_task 实体（litisle_review 库审核任务表，状态机核心）
 * <p>不加 del_flag：审核记录不可物理删除，所有变更走 status 流转</p>
 * <p>唯一键 uk_biz_scene(biz_type,biz_id,scene) + 幂等 upsert：同一内容主体只保留一行任务；
 * 命中已有行时复用同行（重置 status=0 或复审场景置 2、更新 content_hash、retry_count 归零），
 * 历史链路靠 moderation_record（append-only）保留</p>
 * <p>重置例外：仅 content_hash 变化或已有任务终态（status∈{1,3,4}）才重置；命中行 content_hash
 * 未变且非终态（status∈{0,2}）时只复用不重置，防同内容第二条举报打回已认领在审任务</p>
 * <p>MyBatis-Plus：@TableName("review_task") + @TableId(value="id", type=AUTO)</p>
 * <p>枚举字段靠 yml default-enum-type-handler 自动映射</p>
 */
@Data
@Accessors(chain = true)
@TableName("review_task")
public class ReviewTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 业务类型：1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片
     * <p>biz_type=6 时 biz_id = file_meta.id</p>
     */
    @TableField("biz_type")
    private BizTypeEnum bizType;

    /**
     * 业务ID
     * <p>USERNAME 同步拒绝不建任务（仅落 moderation_record），故本列不出现 0</p>
     */
    @TableField("biz_id")
    private Long bizId;

    /**
     * 审核场景（发布场景与 *_RE_REVIEW 复审场景分列，防 upsert 撞键重置已终态任务）
     */
    @TableField("scene")
    private ReviewSceneEnum scene;

    /**
     * DFA 命中：0-未命中 1-HIGH 2-MID
     */
    @TableField("layer1_hit")
    private Layer1HitEnum layer1Hit;

    /**
     * DFA 命中词快照（逗号分隔，审核时固化不随词库热更变化）
     * <p>text：应用层截断保护后落库（超长 varchar 在 strict mode 报错会中断审核写入）</p>
     */
    @TableField("hit_words")
    private String hitWords;

    /**
     * 小审风险：0-未送审 1-低 2-中 3-高（辅助分级供人审队列排序，不承载判定语义）
     */
    @TableField("layer2_risk")
    private Layer2RiskEnum layer2Risk;

    /**
     * 状态：0-待审 1-自动通过 2-人工待审 3-通过 4-驳回（终态 {1,3,4}）
     */
    @TableField("status")
    private ReviewTaskStatusEnum status;

    /**
     * 机审三分态冗余：0-未审 1-PASS 2-REJECT 3-SUSPICIOUS（人审队列列表直读，免 JOIN）
     * <p>重置任务时须同步清 0（旧证据属旧内容，防队列展示与排序张冠李戴）</p>
     */
    @TableField("mod_verdict")
    private VerdictEnum modVerdict;

    /**
     * MQ 重试次数，耗尽转人审（DLX 消费者累加写回，与消息头 retryCount 对账）
     */
    @TableField("retry_count")
    private Integer retryCount;

    /**
     * 内容指纹 SHA256（幂等入口 + 审核缓存复用入口）
     * <p>文本=标题+正文；图片=文件 sha256；文章级图片复审=全部配图 sha256 聚合指纹</p>
     */
    @Size(max = 64, message = "内容指纹长度不能超过 64 个字符")
    @TableField("content_hash")
    private String contentHash;

    /**
     * 认领审核员ID
     */
    @TableField("assignee")
    private Long assignee;

    /**
     * 认领时间（sweeper 按此判定认领超时回池，勿用 update_time——详情查看会刷新 update_time）
     */
    @TableField("claim_time")
    private LocalDateTime claimTime;

    /**
     * 决策者ID
     */
    @TableField("decided_by")
    private Long decidedBy;

    /**
     * 审核意见/修改建议
     */
    @Size(max = 500, message = "审核意见长度不能超过 500 个字符")
    @TableField("opinion")
    private String opinion;

    /**
     * 终审落库时间（decide 写入；申诉窗口基准与超时判定的权威时间源）
     */
    @TableField("decide_time")
    private LocalDateTime decideTime;

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