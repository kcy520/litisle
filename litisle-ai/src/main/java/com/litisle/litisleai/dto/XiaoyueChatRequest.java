package com.litisle.litisleai.dto;

import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.Data;

import java.io.Serializable;

/**
 * 小悦统一入口请求（板块二 2.3：POST /ai/xiaoyue/chat；板块三 3.3 复用为润色/结构分析直连请求体）
 *
 * <p>【purpose 口径】P6 定档：purpose 由功能入口预传入，Agent 循环继承，不做路由 LLM 调用；
 * 本入口支持 SUMMARY / TAGGING / QA / POLISH / STRUCTURE（板块三扩充）。</p>
 *
 * <p>【会话口径】sessionId 非空走多轮记忆（板块三 3.2 Redis ChatMemory + DB 存档）；
 * 为空则无会话一次性请求（memoryId 用 requestId 一次性键，不落 ai_session/ai_message）。</p>
 *
 * <p>【身份口径】userId 属于登录态数据，禁止由客户端传入：controller 从 Sa-Token 会话取当前用户，
 * 作为独立入参交 service 收口（配额归属/计量留痕/会话归属校验）；未登录时为 null（仅 QA 允许匿名）。</p>
 *
 * @author litisle
 */
@Data
public class XiaoyueChatRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求幂等键（业务侧生成，重试复用同一键；计量幂等键组成，必传） */
    private String requestId;

    /** 用途打标（入口预传入；/chat 支持 SUMMARY/TAGGING/QA；polish/structure 端点定死覆写） */
    private AiPurposeEnum purpose;

    /** 会话 ID（可空；非空走多轮记忆并落 ai_session/ai_message，为空则一次性请求） */
    private Long sessionId;

    /** 关联文章 ID（摘要/标签/结构分析必传；润色可空——润色选中文本；问答可选） */
    private Long articleId;

    /**
     * 会话结果集序号（板块九：可空——读后摘要/大纲导航经序号引用定位「最近一次推荐/检索
     * 结果集」中的文章；与 articleId 二选一，结果集过期/越界由工具明确提示重新检索）
     */
    private Integer articleSeq;

    /** 用户消息（问答/润色对象/结构分析补充要求；摘要/标签场景可空——由 articleId 定位任务） */
    private String message;

    /** 业务场景快照（可空，计量明细留痕用） */
    private String scene;

    /** 匿名 IP 哈希（板块四 4.2/P5：userId 为空的匿名问答必传，网关脱敏后传入，AI 不存原文） */
    private String anonIpHash;
}
