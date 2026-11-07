package com.litisle.litislecontent.service.impl;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislecontent.constant.ContentBizConstant;
import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.domain.dto.FeedFeedbackDTO;
import com.litisle.litislecontent.domain.entity.FeedFeedback;
import com.litisle.litislecontent.mapper.FeedFeedbackMapper;
import com.litisle.litislecontent.service.FeedFeedbackService;
import com.litisle.litislecontent.service.support.FeedFeedbackSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 推荐流负反馈服务实现（批次十一 11.11）
 *
 * <p>口径：</p>
 * <ul>
 *   <li>三类维度共用一张表：不感兴趣（内容ID）/ 屏蔽作者（作者ID）/ 屏蔽分类（分类名）；</li>
 *   <li>提交幂等靠唯一键（重复插入被忽略）；撤销幂等靠删除不存在的行返回 0；</li>
 *   <li>支持撤销：误点或改变主意很常见，撤销成本仅一条 DELETE，且唯一键保证重复操作安全；</li>
 *   <li>写入或撤销后只失效该用户该流的名单缓存，不清理快照、不做全量 evict。</li>
 * </ul>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedFeedbackServiceImpl implements FeedFeedbackService {

    /** 负反馈 Mapper（幂等写入/撤销） */
    private final FeedFeedbackMapper feedFeedbackMapper;

    /** 名单读取组件（负责按用户键失效缓存） */
    private final FeedFeedbackSupport feedFeedbackSupport;

    /**
     * 提交一条负反馈（幂等）。
     *
     * @param userId 当前登录用户ID
     * @param dto    反馈入参
     */
    @Override
    public void submit(Long userId, FeedFeedbackDTO dto) {
        // 步骤1：参数归一化与校验（非法类型/目标直接拒绝）
        FeedFeedback feedback = normalize(userId, dto);
        // 步骤2：幂等写入（唯一键重复时返回 0，按成功处理）
        int inserted = feedFeedbackMapper.insertIgnore(feedback);
        // 步骤3：只失效当前用户当前流的名单缓存（下一次刷新生效）
        feedFeedbackSupport.invalidate(userId, feedback.getStream());
        log.info("[负反馈] 提交完成: userId={}, type={}, stream={}, inserted={}",
                userId, feedback.getFeedbackType(), feedback.getStream(), inserted);
    }

    /**
     * 撤销一条负反馈（幂等）。
     *
     * @param userId 当前登录用户ID
     * @param dto    反馈入参
     */
    @Override
    public void cancel(Long userId, FeedFeedbackDTO dto) {
        // 步骤1：参数归一化与校验（与提交共用同一套规则）
        FeedFeedback feedback = normalize(userId, dto);
        // 步骤2：按目标删除（本来不存在也按成功处理）
        int deleted = feedFeedbackMapper.deleteByTarget(userId, feedback.getFeedbackType(),
                feedback.getStream(), feedback.getTargetId(), feedback.getTargetKey());
        // 步骤3：同样只失效该用户该流的名单缓存
        feedFeedbackSupport.invalidate(userId, feedback.getStream());
        log.info("[负反馈] 撤销完成: userId={}, type={}, stream={}, deleted={}",
                userId, feedback.getFeedbackType(), feedback.getStream(), deleted);
    }

    /**
     * 参数归一化与校验：把三种类型的目标映射成统一的（targetId, targetKey）。
     *
     * @param userId 当前登录用户ID
     * @param dto    原始入参
     * @return 待写入/撤销的实体（未写库，仅填充字段）
     */
    private FeedFeedback normalize(Long userId, FeedFeedbackDTO dto) {
        // 类型必填且必须是三类之一（避免前端传错时静默写入脏数据）
        Integer type = dto == null ? null : dto.getFeedbackType();
        if (type == null || type < ContentBizConstant.FEEDBACK_TYPE_CONTENT
                || type > ContentBizConstant.FEEDBACK_TYPE_CATEGORY) {
            throw new BizException(ErrorCode.PARAM_ERROR, "反馈类型非法");
        }
        // 流默认文章流；只接受 article/book
        String stream = StringUtils.hasText(dto.getStream()) ? dto.getStream() : FeedConstant.STREAM_ARTICLE;
        if (!FeedConstant.STREAM_ARTICLE.equals(stream) && !FeedConstant.STREAM_BOOK.equals(stream)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "内容流非法");
        }

        // 待写入实体（分类用 targetKey，其余用 targetId）
        FeedFeedback feedback = new FeedFeedback()
                .setUserId(userId)
                .setFeedbackType(type)
                .setStream(stream)
                .setCreateTime(LocalDateTime.now());

        // 分类类型：分类名必填且有长度上限（与标签/分类名口径一致 ≤64）
        if (type == ContentBizConstant.FEEDBACK_TYPE_CATEGORY) {
            String category = dto.getTargetKey() == null ? null : dto.getTargetKey().trim();
            if (!StringUtils.hasText(category) || category.length() > 64) {
                throw new BizException(ErrorCode.PARAM_ERROR, "屏蔽分类非法");
            }
            return feedback.setTargetId(0L).setTargetKey(category);
        }

        // 内容/作者类型：ID 必须为正数
        Long targetId = dto.getTargetId();
        if (targetId == null || targetId <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "屏蔽目标非法");
        }
        return feedback.setTargetId(targetId).setTargetKey("");
    }
}
