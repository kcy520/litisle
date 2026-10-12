package com.litisle.litislereview.domain.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.ModerationCategoryEnum;
import com.litisle.litislereview.enums.ModerationProviderEnum;
import com.litisle.litislereview.enums.ModTypeEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * moderation_record_archive 实体（审核记录归档表，只读历史存储——6-4 §4.4.1）
 *
 * 0 ──────────── 6个月 ────────────────────────────→ 时间轴
 * │←── 热表 moderation_record ──→│
 * │   活跃期，正常读写查询          │
 *                                │←── 归档表 moderation_record_archive ──→│
 *                                │ 到期当天迁入，之后只进不出，没有过期删除这一说 │
 *
 *
 * <p>列口径与 ModerationRecord 逐列对齐；主键 id = 原热表 id 原样保留（IdType.INPUT 禁自增），
 * 审计链路不断，按 id 幂等迁档（重跑同 id 主键冲突即暴露）</p>
 * <p>写入唯一入口 = ModerationRecordArchiveSweeper 原子迁档事务；应用账号 DBA 层仅授
 * INSERT/SELECT（无 UPDATE/DELETE，防误改误删历史凭证），本实体不提供任何更新语义</p>
 * <p>MyBatis-Plus：@TableName("moderation_record_archive") + @TableId(value="id", type=INPUT)</p>
 */
@Data
@Accessors(chain = true)
@TableName("moderation_record_archive")
public class ModerationRecordArchive implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记录ID（原 moderation_record.id 原样保留，IdType.INPUT 直插不重发）
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 审核方式：1-关键词DFA 2-LLM语义 3-本地图片模型 4-云API 5-人工复审 6-申诉改判
     */
    @TableField("mod_type")
    private ModTypeEnum modType;

    /**
     * 业务类型（同 review_task.biz_type）：1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片
     */
    @TableField("biz_type")
    private ModerationBizTypeEnum bizType;

    /**
     * 业务ID（biz_type=6 时为 file_meta.id；USERNAME 取证约定 biz_id=0）
     */
    @TableField("biz_id")
    private Long bizId;

    /**
     * 关联审核任务ID（同步闸门可无任务直审，如 USERNAME）
     */
    @TableField("review_task_id")
    private Long reviewTaskId;

    /**
     * 审核场景（同 review_task.scene；场景列自包含免 JOIN）
     */
    @TableField("scene")
    private ReviewSceneEnum scene;

    /**
     * 内容指纹 SHA256（审核缓存查重键口径同热表）
     */
    @TableField("content_hash")
    private String contentHash;

    /**
     * 审核提供方（图片：onnx/aliyun；文本：llm；人工/DFA 为空）
     */
    @TableField("provider")
    private ModerationProviderEnum provider;

    /**
     * 词库版本快照（文本记录写入=sensitive_word_version.version；图片/人工为空）
     */
    @TableField("wordlib_ver")
    private Long wordlibVer;

    /**
     * 判定口径版本快照（图片=ONNX 模型版本/云 API 接口版本；文本=LLM few-shot prompt 版本）
     */
    @TableField("model_version")
    private String modelVersion;

    /**
     * 判定：1-PASS 2-REJECT 3-SUSPICIOUS
     */
    @TableField("verdict")
    private VerdictEnum verdict;

    /**
     * 违规类目（机审统一映射枚举）：politics/porn/abuse/ad/none
     */
    @TableField("category")
    private ModerationCategoryEnum category;

    /**
     * DFA 命中词快照（凭证自包含便于导出审计）
     */
    @TableField("hit_words")
    private String hitWords;

    /**
     * 判定理由（作者申诉与管理员复审依据）
     */
    @TableField("reason")
    private String reason;

    /**
     * 审核凭证ID（云厂商 traceId / LLM 请求ID）——对外合规凭证
     */
    @TableField("provider_trace_id")
    private String providerTraceId;

    /**
     * 网关→MQ→消费端链路追踪ID——内部链路追踪
     */
    @TableField("trace_id")
    private String traceId;

    /**
     * 操作人（0-系统机审）
     */
    @TableField("operator_id")
    private Long operatorId;

    /**
     * 操作人名称（审计冗余）
     */
    @TableField("operator_name")
    private String operatorName;

    /**
     * 审核耗时（观测 Timer 旁路佐证）
     */
    @TableField("cost_ms")
    private Integer costMs;

    /**
     * 记录时间（=原热表记录时间，合规留存期以此为准）
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}
