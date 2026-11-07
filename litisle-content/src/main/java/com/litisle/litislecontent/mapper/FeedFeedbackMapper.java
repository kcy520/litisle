package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.FeedFeedback;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * feed_feedback 负反馈 Mapper（批次十一 11.11）
 *
 * <p>职责：幂等写入（唯一键冲突忽略）、按目标撤销、按用户+时间窗读取名单。</p>
 *
 * @author litisle
 */
@Mapper
public interface FeedFeedbackMapper extends BaseMapper<FeedFeedback> {

    /**
     * 幂等写入一条屏蔽记录（命中唯一键时忽略，不抛异常）。
     *
     * @param feedback 负反馈实体（userId/类型/流/目标/时间由服务层填充）
     * @return 影响行数（0=重复提交被忽略，1=新写入）
     */
    @Insert("""
            insert ignore into feed_feedback
                (user_id, feedback_type, stream, target_id, target_key, create_time)
            values
                (#{userId}, #{feedbackType}, #{stream}, #{targetId}, #{targetKey}, #{createTime})
            """)
    int insertIgnore(FeedFeedback feedback);

    /**
     * 按目标撤销一条屏蔽记录（同目标重复撤销幂等）。
     *
     * @param userId       用户ID
     * @param feedbackType 反馈类型
     * @param stream       内容流（article/book）
     * @param targetId     内容ID或作者ID（分类类型为 0）
     * @param targetKey    分类名（内容/作者类型为空串）
     * @return 删除行数（0=本来就不存在）
     */
    @Delete("""
            delete from feed_feedback
             where user_id = #{userId}
               and feedback_type = #{feedbackType}
               and stream = #{stream}
               and target_id = #{targetId}
               and target_key = #{targetKey}
            """)
    int deleteByTarget(@Param("userId") Long userId,
                       @Param("feedbackType") Integer feedbackType,
                       @Param("stream") String stream,
                       @Param("targetId") Long targetId,
                       @Param("targetKey") String targetKey);
}
