package com.litisle.litislecontent.service;

import com.litisle.litislecontent.domain.dto.FeedFeedbackDTO;

/**
 * 推荐流负反馈服务（批次十一 11.11）
 *
 * <p>提供两个幂等操作：提交屏蔽、撤销屏蔽。必须登录后调用。
 * 屏蔽只影响建池与切片，不修改已有快照内容。</p>
 *
 * @author litisle
 */
public interface FeedFeedbackService {

    /**
     * 提交一条负反馈（重复提交按成功处理，不重复写行）。
     *
     * @param userId 当前登录用户ID
     * @param dto    反馈入参（类型/流/目标）
     */
    void submit(Long userId, FeedFeedbackDTO dto);

    /**
     * 撤销一条负反馈（本来不存在也按成功处理）。
     *
     * @param userId 当前登录用户ID
     * @param dto    反馈入参（类型/流/目标）
     */
    void cancel(Long userId, FeedFeedbackDTO dto);
}
