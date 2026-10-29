package com.litisle.litislecontent.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.litislecontent.domain.vo.FavoriteItemVO;

/**
 * 收藏域服务（阶段16：一期 content 临时承载单层收藏，social 收藏夹立项后迁移）。
 *
 * 职责：
 * 收藏/取消收藏（幂等）、我的收藏分页（多态 join 装配）、收藏状态查询；
 * 写入前做同域目标可见性校验（文章按详情可见性口径，书籍下架仅作者可收藏），
 * 不允许收藏自己看不到的内容。
 *
 * @author litisle
 */
public interface FavoriteService {

    /**
     * 收藏目标（幂等：重复收藏视为成功）。
     *
     * @param userId     收藏用户ID（Controller @SaCheckLogin 保证非空）
     * @param targetType 目标类型（1-文章 2-书籍；非法值抛 PARAM_ERROR）
     * @param targetId   目标ID
     * @throws com.litisle.commoncore.exception.BizException
     *         PARAM_ERROR 类型非法 / ARTICLE_NOT_FOUND、BOOK_NOT_FOUND 目标不存在或不可见 /
     *         REMOTE_SERVICE_ERROR social 不可用（fail-fast，不静默放行）
     */
    void favorite(Long userId, Integer targetType, Long targetId);

    /**
     * 取消收藏（幂等：未收藏也视为成功）。
     *
     * @param userId     用户ID
     * @param targetType 目标类型（非法值抛 PARAM_ERROR）
     * @param targetId   目标ID
     */
    void unfavorite(Long userId, Integer targetType, Long targetId);

    /**
     * 我的收藏分页（targetType null=全部混合时间线）。
     *
     * @param userId     用户ID
     * @param targetType 目标类型过滤（null=全部 / 1=文章 / 2=书籍；非法值抛 PARAM_ERROR）
     * @param pageNo     页码（从 1 开始，小于 1 按 1 处理）
     * @param pageSize   每页条数（超过上限钳制到 PageConstant.MAX_PAGE_SIZE）
     * @return 收藏列表项分页；无收藏返回空页，不抛异常
     */
    Page<FavoriteItemVO> pageMyFavorites(Long userId, Integer targetType, long pageNo, long pageSize);

    /**
     * 收藏状态查询（匿名返回 false）。
     *
     * @param userId     用户ID（null=匿名）
     * @param targetType 目标类型（非法值抛 PARAM_ERROR）
     * @param targetId   目标ID
     * @return true=已收藏；false=未收藏或匿名
     */
    boolean isFavorited(Long userId, Integer targetType, Long targetId);
}
