package com.litisle.litislecontent.domain.dto;

import lombok.Data;

/**
 * 负反馈提交/撤销入参（批次十一 11.11）
 *
 * <p>三种类型共用同一入参：</p>
 * <ul>
 *   <li>不感兴趣：feedbackType=1，targetId=内容ID；</li>
 *   <li>屏蔽作者：feedbackType=2，targetId=作者ID；</li>
 *   <li>屏蔽分类：feedbackType=3，targetKey=分类名。</li>
 * </ul>
 *
 * @author litisle
 */
@Data
public class FeedFeedbackDTO {

    /** 反馈类型：1-不感兴趣 2-屏蔽作者 3-屏蔽分类（为空或非法直接拒绝） */
    private Integer feedbackType;

    /** 内容流：article/book（决定内容ID/作者ID的命名空间；为空默认 article） */
    private String stream;

    /** 内容ID或作者ID（类型 1/2 必填且必须为正数；类型 3 忽略） */
    private Long targetId;

    /** 分类名（类型 3 必填；其余类型忽略） */
    private String targetKey;
}
