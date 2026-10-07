package com.litisle.litisleapi.dto.ai;

import lombok.Data;

/**
 * AI 文本对话响应
 *
 * <p>content 为模型原始文本输出，调用方自行解析（审核侧按 JSON 白名单校验，
 * 解析失败转 SUSPICIOUS）；providerTraceId 为模型服务请求 ID，用于合规出证与
 * moderation_record 留痕，本地通道可能为 null</p>
 */
@Data
public class AiChatResponseDTO {

    /** 模型文本输出（原始，未加工） */
    private String content;

    /** 实际使用的模型标识（版本对齐观测用） */
    private String model;

    /** 模型服务请求 ID（合规凭证，可为 null） */
    private String providerTraceId;
}
