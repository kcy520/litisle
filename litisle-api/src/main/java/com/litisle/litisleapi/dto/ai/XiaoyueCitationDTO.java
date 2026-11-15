package com.litisle.litisleapi.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 小悦问答引用卡片（板块四 4.2：站内问答引用溯源，仅放引用元数据不放正文原文）
 *
 * <p>【口径】引用必须可溯源（Q75）——前端按 articleId 跳转文章；
 * removed=true 表示该引用在回查时已不可见（下架即失效），前端弱化或隐藏展示。</p>
 *
 * @author litisle
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class XiaoyueCitationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 引用文章 ID（前端跳转溯源依据） */
    private Long articleId;

    /** 引用文章标题（卡片展示） */
    private String title;

    /** 引用时内容指纹（版本比对依据，Q75；可空） */
    private String contentHash;

    /** 引用失效标记（true=回查时已下架/不可见，前端弱化展示） */
    private boolean removed;
}
