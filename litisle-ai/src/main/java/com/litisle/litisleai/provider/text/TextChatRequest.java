package com.litisle.litisleai.provider.text;

import lombok.Builder;
import lombok.Data;

/**
 * 文本模型调用入参（provider 层内部命令对象，最小必要原则的通道面）
 *
 * <p>【出站红线】通道面仅收 system + user 双段 prompt 与采样温度，
 * 不接收用户身份字段（用户ID/昵称/IP 等）——出站请求天然不携带用户身份信息；
 * 未公开稿件脱敏过滤器（P1 定档）在 provider 出站前统一执行，随板块二接入本层。</p>
 */
@Data
@Builder
public class TextChatRequest {

    /** 系统提示词（角色/规则/输出格式约定） */
    private String systemPrompt;

    /** 用户消息（待处理数据，调用方负责包裹与清洗） */
    private String userPrompt;

    /**
     * 采样温度（null 时使用模型默认配置；机审/结构化产出传 0 保证确定性）
     */
    private Double temperature;
}
