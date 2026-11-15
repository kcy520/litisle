package com.litisle.litisleai.provider.text;

import lombok.Builder;
import lombok.Data;

/**
 * 文本模型调用出参（计量与留痕的数据来源）
 */
@Data
@Builder
public class TextChatResult {

    /** 模型文本输出（原始，未加工；结构校验由调用方负责） */
    private String content;

    /** 实际使用的模型标识（计量明细 model_version / prompt_version 同值） */
    private String modelVersion;

    /** 模型服务请求 ID（合规凭证，供应商未返回时为 null） */
    private String providerTraceId;

    /** 输入 token 数（供应商未返回时为 null，计量按 0 落库） */
    private Integer tokenIn;

    /** 输出 token 数（供应商未返回时为 null，计量按 0 落库） */
    private Integer tokenOut;
}
