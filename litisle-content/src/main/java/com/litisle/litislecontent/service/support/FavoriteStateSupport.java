package com.litisle.litislecontent.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litislecontent.domain.entity.Favorite;
import com.litisle.litislecontent.mapper.FavoriteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 收藏状态查询支撑组件（阶段16，与 ReadingHistorySupport 并列）。
 *
 * 作用：
 * 收藏状态等值查询的单点收口——只依赖 FavoriteMapper 做 uk_user_target 等值 count，
 * 供详情 Service 回填 VO 的 favorited 字段与 FavoriteServiceImpl#isFavorited 委托复用。
 *
 * 依赖方向（防循环依赖）：
 * 详情 Service → FavoriteStateSupport → FavoriteMapper（只读，不反向依赖任何 Service）；
 * FavoriteServiceImpl 的写入校验依赖另行注入 ArticleMapper/BookMapper 等，与本组件无交集。
 *
 * @author litisle
 */
@Component
@RequiredArgsConstructor
public class FavoriteStateSupport {

    /** 收藏表 Mapper（等值 count 承接方） */
    private final FavoriteMapper favoriteMapper;

    /**
     * 查询用户对指定目标的收藏状态。
     *
     * @param userId     用户ID（调用方保证非空；匿名场景应在调用前短路返回 false）
     * @param targetType 目标类型（1-文章 2-书籍）
     * @param targetId   目标ID
     * @return true=已收藏；false=未收藏
     */
    public boolean isFavorited(Long userId, Integer targetType, Long targetId) {
        // uk_user_target 唯一键等值查询，命中即已收藏
        Long count = favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId)
                .eq(Favorite::getTargetType, targetType)
                .eq(Favorite::getTargetId, targetId));
        return count != null && count > 0;
    }
}
