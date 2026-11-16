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
}
