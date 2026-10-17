package com.litisle.litisleapi.dto.content;


import lombok.Data;

import java.io.Serializable;

/**
 * 文章计数回写 DTO（social 聚合侧单点回写跨服务契约，§4.6）
 *
 * 【设计要点】
 * - 幂等绝对值 SET：三计数均为全量目标值（非增量），重复调用结果收敛；
 *   并发覆盖由 social 聚合侧单点回写规避（Redis 缓冲→定时聚合→单点 SET），content 不做累加
 * - 仅非空字段更新（MP update-strategy not_null 口径），articleId 必填
 *
 * @author litisle
 */
@Data
public class ArticleCounterUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文章ID（必填） */
    private Long articleId;

    /** 浏览数（绝对值，null=不更新） */
    private Long viewCnt;

    /** 点赞数（绝对值，null=不更新） */
    private Long likeCnt;

    /** 评论数（绝对值，null=不更新） */
    private Long commentCnt;
}
