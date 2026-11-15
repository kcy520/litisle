package com.litisle.litisleapi.dto.ai;



import lombok.Data;

/**
 * AI 文本对话请求（OpenAI 兼容通道的通用契约，与审核业务无关）
 *
 * <p>契约设计：AiClient 只提供「system + user 双段 prompt」的最小对话面，
 * few-shot 示例、防注入包裹、JSON 输出格式约定全部由调用方（如 review 侧
 * LlmTextModerationAdapter）在 prompt 中自行组织，通道层不做任何业务加工——
 * 保证通道对 RAG 等后续 AI 能力同样可复用</p>
 */
@Data
public class AiChatRequestDTO {

    /** 系统提示词（角色/规则/输出格式约定） */
    private String systemPrompt;

    /** 用户消息（待处理数据，调用方负责包裹与清洗） */
    private String userPrompt;

    /** 采样温度（审核类调用固定 0，保证输出确定性） */
    private Double temperature;

    /**
     * 用途打标（必传，缺省提供方侧拒绝并告警，防「忘传」被默认归机审）；
     * 计费与预算只认前五类，OTHER 仅兜底落明细（口径见 {@link AiPurposeEnum}）
     */
    private AiPurposeEnum purpose;

    /**
     * 请求幂等键（业务侧生成，重试复用同一键；如机审切片 taskId:chunkIndex）。
     * 计量幂等键 = requestId + 调用序号（callSeq 由提供方侧分配），防重复计费
     */
    private String requestId;

    /** 业务场景快照（可空，如 ARTICLE_FEATURED/COMMENT/USERNAME，与 review_task.scene 同名） */
    private String scene;
}
