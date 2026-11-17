package com.litisle.litisleai.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 相似文章推荐请求（板块五 5.1：POST /ai/xiaoyue/similar；F8 纯检索形态）
 *
 * <p>【口径】userId 不从请求体传入：controller 从 Sa-Token 会话取当前用户（文章页匿名浏览时为空，
 * 计量归属 user_id 为空，与机审口径一致）；requestId 必传（纯检索 token=0 也落一条计量明细，
 * 幂等键需要）；推荐只读，无预算/配额拦截。</p>
 *
 * @author litisle
 */
@Data
public class XiaoyueSimilarRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请求幂等键（业务侧生成，计量明细 requestId 组成部分，必传） */
    private String requestId;

    /** 被浏览文章 ID（推荐排除自身，必传） */
    private Long articleId;

    /** 业务场景快照（可空，计量明细留痕用） */
    private String scene;

    /**
     * 自由条件描述（板块八 1.1：可空——空 → L0 直通固定相似检索 0 次 LLM；非空 → L1 条件提取管道，
     * 提取失败/超时降级 L0 并在响应标注「未识别自定义条件」，不报错）
     */
    private String freeText;

    /**
     * 会话 ID（板块九 1.1：可空——非空时本次推荐/检索结果记入会话结果集序号映射，
     * 供会话内「第 N 篇」类结果动作与读后服务引用；为空不记录）
     */
    private Long sessionId;
}
