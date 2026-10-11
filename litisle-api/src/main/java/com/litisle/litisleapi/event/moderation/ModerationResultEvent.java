package com.litisle.litisleapi.event.moderation;



import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审核结果事件（MQ 事件 Schema，契约库定义、review 服务发布、下游 file/内容/社交 订阅）
 *
 * 【用途】
 * - file：回写 safe_flag（REJECT→0 走物理隔离 / PASS→1 / SUSPICIOUS→3）
 * - 内容/社交：文章复审唤醒、评论/私信回写（后续扩展）
 *
 * 【事件 Schema 公共字段】（对齐 Api-Architecture.md §7.1，全部事件必须有）
 * - eventId    事件唯一 ID（UUID，消费端幂等去重；本事件依赖业务幂等可免 SETNX）
 * - eventTime  事件发生时间
 * - traceId    链路追踪 ID（从上下文透传）
 * - version    Schema 版本号（当前固定 1）
 *
 * 【业务字段】
 * - bizType    审核业务类型（review 侧 ModerationBizTypeEnum code；6=图片，file 侧仅消费此值）
 * - bizId      业务ID（图片时即 fileId）
 * - verdict    终局判定（review 侧 VerdictEnum code：1-PASS 2-REJECT 3-SUSPICIOUS，0-未审不发布）
 * - reviewTaskId 关联审核任务ID（链路追踪/对账用）
 *
 * 【序列化】JSON（Jackson2JsonMessageConverter），本类仅承载字段，不写业务逻辑；
 * 禁止 @Builder（项目 DTO 规范），用 @Data + 手动赋值。
 */
@Data
public class ModerationResultEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一 ID（UUID，消费端幂等依据之一） */
    private String eventId;

    /** 事件发生时间 */
    private LocalDateTime eventTime;

    /** 链路追踪 ID */
    private String traceId;

    /** Schema 版本号（当前固定 1） */
    private Integer version;

    /** 审核业务类型（review 侧 ModerationBizTypeEnum code；6=图片） */
    private Integer bizType;

    /** 业务ID（图片时即 fileId） */
    private String bizId;

    /** 终局判定（1-PASS 2-REJECT 3-SUSPICIOUS，口径同 review 侧 VerdictEnum） */
    private Integer verdict;

    /** 关联审核任务ID */
    private Long reviewTaskId;

    /** 审核场景码（同 review_task.scene；复审与发布任务同 biz 同 id 并存，缺 scene 定位歧义，3-4.2） */
    private String scene;

    /** 内容指纹 SHA256（3-4.2 口径锁定：必须是本次实际送审的指纹，非编辑后新值；content 侧 last-hash 免审键组成段） */
    private String contentHash;

    /** 判定时词库版本快照（3-4.2：错填则 last-hash 免审键落位错误、免审机制失效；重投补发场景可空，宁多审不漏审） */
    private Long wordlibVer;

    /** 违规类目（机审统一映射枚举：politics/porn/abuse/ad/none；LLM 不可信输出已由适配器白名单校验） */
    private String category;

    /** 判定理由（LLM 透传不可信输出，发布前由 publisher 剥离 \r\n + 截断 500） */
    private String reason;
}