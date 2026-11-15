package com.litisle.litisleai.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 小悦直连管道请求（板块二 2.5：POST /ai/xiaoyue/summary 与 /ai/xiaoyue/tag）
 *
 * <p>【端点口径】固定管道直连通道（P10 定档）：一次性动作，不建会话、不写 ai_session/ai_message；
 * 同时作为 2.3 Agent 循环异常时的降级通道（降级链路同样计量）。</p>
 *
 * @author litisle
 */
@Data
public class XiaoyueSuggestRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 发起用户 ID（用户级配额与计量归属，必传） */
    private Long userId;

    /** 请求幂等键（业务侧生成，重试复用同一键；建议态缓存 key 亦用它，必传） */
    private String requestId;

    /** 关联文章 ID（直连管道按此回查稿件，必传） */
    private Long articleId;

    /** 业务场景快照（可空，计量明细留痕用） */
    private String scene;
}
