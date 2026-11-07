package com.litisle.litislecontent.service;

/**
 * 点赞域服务（批次十一 11.8：一期 content 临时承载点赞，social 模块立项后迁移）。
 *
 * 职责：
 * 点赞/取消点赞（幂等、作者自赞硬禁止、目标可见性校验、单用户频控）、
 * 同步 content 统计表点赞计数，并在事务提交后发布 search 索引同步事件。
 *
 * @author litisle
 */
public interface LikeService {

    /**
     * 点赞目标（幂等：重复点赞视为成功且不重复加计数）。
     *
     * @param userId     点赞用户ID（Controller @SaCheckLogin 保证非空）
     * @param targetType 目标类型（1-文章 2-书籍；非法值抛 PARAM_ERROR）
     * @param targetId   目标ID
     * @throws com.litisle.commoncore.exception.BizException
     *         PARAM_ERROR 类型非法 / LIKE_TARGET_NOT_VISIBLE 目标不存在或不可见 /
     *         LIKE_SELF_FORBIDDEN 作者给自己点赞 / TOO_MANY_REQUESTS 单用户频控触发
     */
    void like(Long userId, Integer targetType, Long targetId);

    /**
     * 取消点赞（幂等：未点赞也视为成功且不扣减计数）。
     *
     * <p>不做目标可见性校验：内容被删除/转私密/下架后仍允许用户清理自己的点赞记录。</p>
     *
     * @param userId     用户ID
     * @param targetType 目标类型（非法值抛 PARAM_ERROR）
     * @param targetId   目标ID
     * @throws com.litisle.commoncore.exception.BizException PARAM_ERROR 类型非法 / TOO_MANY_REQUESTS 频控触发
     */
    void unlike(Long userId, Integer targetType, Long targetId);
}
