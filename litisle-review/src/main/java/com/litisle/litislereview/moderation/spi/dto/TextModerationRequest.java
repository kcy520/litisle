package com.litisle.litislereview.moderation.spi.dto;


import lombok.Data;

/**
 * 文本审核入参（SPI 门面）
 * <p>content 为单片待审文本：标题与正文拼接后一并送审（hash 含标题但送审漏标题时，
 * 违规语义藏标题可躲过语义层）；超长正文按段落分片/字符窗口硬切由消费端负责，
 * 本对象收到的已是切分后单片</p>
 *
 * <p>taskId/chunkIndex/scene 供 LLM 适配器组装计量幂等键与用途打标（板块一 1.4 契约）：
 * requestId = taskId:chunkIndex（确定性切分，延迟重试复用同一键不重复计费），
 * purpose 由适配器固定 MODERATION_TEXT，scene 与 review_task.scene 同名快照</p>
 */
@Data
public class TextModerationRequest {

    /** 待审文本（单片） */
    private String content;

    /** 审核任务 ID（review_task.id，LLM 通道计量幂等键 requestId 的前段；缺失即调用方接线错误） */
    private Long taskId;

    /** 分片序号（从 0 递增；LLM 通道计量幂等键 requestId 的后段） */
    private Integer chunkIndex;

    /** 业务场景快照（与 review_task.scene 同名，可空，透传给 AI 侧计量明细 scene 字段） */
    private String scene;

}