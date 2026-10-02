package com.litisle.litislereview.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.ModTypeEnum;
import com.litisle.litislereview.enums.ModerationCategoryEnum;
import com.litisle.litislereview.enums.ModerationProviderEnum;
import com.litisle.litislereview.enums.ReviewSceneEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * moderation_record 实体（审核记录表，append-only 不可改不可删）
 * <p>《网络安全法》留痕硬凭证留存≥6 个月；机审+人审+申诉改判全程追加，改判/申诉均追加新行，
 * 与 review_task 状态机分离</p>
 * <p>命中词/凭证/理由/场景四列自包含，单行即可对外出具审核凭证无需 JOIN</p>
 * <p>MyBatis-Plus：@TableName("moderation_record") + @TableId(value="id", type=AUTO)</p>
 */
@Data
@Accessors(chain = true)
@TableName("moderation_record")
public class ModerationRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记录ID
     * <p>MyBatis-Plus 自增主键</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
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
     * 业务ID
     * <p>biz_type=6 时为 file_meta.id；USERNAME 注册被拒时用户尚不存在，约定 biz_id=0</p>
     */
    @TableField("biz_id")
    private Long bizId;

    /**
     * 关联审核任务ID（同步闸门可无任务直审，如 USERNAME）
     */
    @TableField("review_task_id")
    private Long reviewTaskId;

    /**
     * 审核场景（同 review_task.scene；同 biz_type+biz_id 可并存文本/图片复审多场景，场景列自包含免 JOIN）
     */
    @TableField("scene")
    private ReviewSceneEnum scene;

    /**
     * 内容指纹 SHA256（审核缓存查重键；文本=标题+正文；图片=文件 sha256；文章级复审=聚合指纹）
     */
    @Size(max = 64, message = "内容指纹长度不能超过 64 个字符")
    @TableField("content_hash")
    private String contentHash;

    /**
     * 审核提供方（图片：onnx/aliyun；文本：llm；人工/DFA 为空）
     * <p>缓存兜底按此过滤，防 onnx→aliyun 切换复用旧结论</p>
     */
    @TableField("provider")
    private ModerationProviderEnum provider;

    /**
     * 词库版本快照（文本记录写入=sensitive_word_version.version；图片/人工为空）
     * <p>缓存兜底按此比对，防词库热更后窗口内旧结论继续放行</p>
     */
    @TableField("wordlib_ver")
    private Long wordlibVer;

    /**
     * 判定口径版本快照（图片=ONNX 模型版本/云 API 接口版本；文本=LLM few-shot prompt 版本）
     * <p>缓存兜底按此比对，防同 provider 下模型/prompt 升级后复用旧结论</p>
     */
    @Size(max = 32, message = "判定口径版本长度不能超过 32 个字符")
    @TableField("model_version")
    private String modelVersion;

    /**
     * 判定：1-PASS 2-REJECT 3-SUSPICIOUS（本表不写 0-未审）
     */
    @TableField("verdict")
    private VerdictEnum verdict;

    /**
     * 违规类目（机审统一映射枚举）：politics/porn/abuse/ad/none
     */
    @TableField("category")
    private ModerationCategoryEnum category;

    /**
     * DFA 命中词快照（冗余自 review_task，凭证自包含便于导出审计；应用层截断保护后落 text）
     */
    @TableField("hit_words")
    private String hitWords;

    /**
     * 判定理由（作者申诉与管理员复审依据，LLM 输出透传）
     */
    @Size(max = 500, message = "判定理由长度不能超过 500 个字符")
    @TableField("reason")
    private String reason;

    /**
     * 审核凭证ID（云厂商 traceId / LLM 请求ID；本地组件与人工为空）——对外合规凭证
     */
    @Size(max = 128, message = "凭证ID长度不能超过 128 个字符")
    @TableField("provider_trace_id")
    private String providerTraceId;

    /**
     * 网关→MQ→消费端链路追踪ID（排障一串到底）——内部链路追踪
     */
    @Size(max = 64, message = "链路追踪ID长度不能超过 64 个字符")
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
    @Size(max = 32, message = "操作人名称长度不能超过 32 个字符")
    @TableField("operator_name")
    private String operatorName;

    /**
     * 审核耗时（观测 Timer 旁路佐证）
     */
    @TableField("cost_ms")
    private Integer costMs;

    /**
     * 记录时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;
}