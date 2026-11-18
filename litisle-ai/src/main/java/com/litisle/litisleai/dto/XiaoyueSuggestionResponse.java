package com.litisle.litisleai.dto;

import com.litisle.litisleapi.dto.ai.XiaoyueCitationDTO;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 小悦建议响应（板块二 2.5：建议态统一出参）
 *
 * <p>【展示铁律（D3/D13）】aiGenerated 恒为 true——响应带「AI 生成/AI 建议」标识，
 * 前端据此渲染标识；message 默认携带提示文案；展示前 DFA 闸门由调用方 content/review 执行。</p>
 *
 * <p>【建议态铁律（D4）】响应内容一律为建议态，不自动写入业务库；
 * 作者编辑/确认后由 content 落库，未确认则丢弃（Redis 建议缓存 30 分钟过期即弃）。</p>
 *
 * @author litisle
 */
@Data
@Builder
public class XiaoyueSuggestionResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求幂等键（建议态缓存回查键） */
    private String requestId;

    /** 用途编码（SUMMARY / TAGGING / QA） */
    private String purpose;

    /** 摘要建议（SUMMARY 场景；≤500 字，service 侧校验） */
    private String summary;

    /** 标签建议（TAGGING 场景；全部在标签池内，service 侧越池校验） */
    private List<String> tags;

    /** 问答回答（QA 场景；未引用站内检索结果时已由模型标注） */
    private String answer;

    /** 润色建议（POLISH 场景，板块三 3.3：润色后的文本建议，作者确认后才替换） */
    private String polished;

    /** 结构分析建议（STRUCTURE 场景，板块三 3.3：结构问题清单与改进建议） */
    private String analysis;

    // ==================== 写作辅助扩展（板块十五） ====================

    /** 改写目标粒度（板块十五 15.1 REWRITE 场景：TITLE/PARAGRAPH/SENTENCE/WORD，其余场景 null） */
    private String targetType;

    /** 改写候选版本列表（板块十五 15.1：每版附一句改动理由；service 侧按粒度校验） */
    private List<com.litisle.litisleai.dto.RewriteCandidate> rewriteCandidates;

    /** 优化建议清单（板块十五 15.1：冗余/歧义/语气/结构诊断，只诊断不贬损——F6/F8 边界） */
    private List<String> rewriteSuggestions;

    /** 文章草稿正文（板块十五 15.2 DRAFT 场景：建议态，AI 不落库——作者采用后由前端带入 content 正常创建流程） */
    private String draft;

    /** 段落结构建议（板块十五 15.2 DRAFT 场景：各段要点与衔接逻辑） */
    private String outline;

    /** 引用卡片列表（QA 场景，板块四 4.2：站内问答引用溯源；仅元数据不放正文原文） */
    private List<XiaoyueCitationDTO> citations;

    /** AI 生成标识（D3/D13：恒 true，前端据此渲染「AI 建议」标识） */
    private boolean aiGenerated;

    /** 降级标记（true=降级管道产出或失败提示，前端可弱化展示） */
    private boolean degraded;

    /** 提示文案（默认「AI 生成内容，仅供参考」；失败时为失败提示，用户可手动填写） */
    private String message;

    /** 实际使用的模型标识（计量留痕同源） */
    private String modelVersion;
}
