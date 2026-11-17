package com.litisle.litisleai.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发布前查重请求（板块十二 4.2：创作侧发布前调用相似检索换调用时机，
 * 相似度超阈值提示重复风险；入口建设归 content 创作页，AI 侧只提供能力端点）
 *
 * <p>【设计说明】查重对象为「待发布草稿」：标题与正文由创作页显式传入（草稿未入
 * 索引/未建切片，不能走取稿工具链路）；explain=true 时额外 1 次 LLM 生成「哪里相似」
 * 说明（0~1 次生成成本口径）。</p>
 *
 * @author litisle
 */
@Data
public class DuplicateCheckRequest {

    /** 请求幂等键（计量明细归属） */
    @NotBlank(message = "requestId 不能为空")
    private String requestId;

    /** 待发布文章标题（草稿标题，必传） */
    @NotBlank(message = "title 不能为空")
    private String title;

    /** 待发布文章正文（草稿正文，必传；服务侧按截断口径参与检索与说明生成） */
    @NotBlank(message = "body 不能为空")
    private String body;

    /** 是否生成「哪里相似」说明（可空默认 false；true 且命中时 +1 次 LLM） */
    private Boolean explain;
}
