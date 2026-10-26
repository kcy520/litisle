package com.litisle.litislecontent.service;


import com.litisle.litislecontent.domain.vo.FeedItemVO;

import java.util.List;

/**
 * 推荐流域服务（缓存三防 + 展示前回查），REST 由 FeedController 调用：
 *
 * List<FeedItemVO> feed(Long userId, int pageNo, int pageSize) // 推荐流分页
 *
 * @author litisle
 */
public interface FeedService {

    /**
     * 推荐流分页（预计算推荐链路，zone 1/2 聚合视图）：
     * 互斥重建（单飞锁）防击穿 + 空结果短缓存防穿透 + TTL ±10% 抖动防雪崩；
     * 展示前回查 status=2 且 visibility=1，已下架/转私密即时剔除（防残留曝光）
     *
     * @param userId   当前登录用户ID（个性化排序预留，一期仅缓存维度）
     * @param pageNo   页码（1 起）
     * @param pageSize 页大小（默认 feed.page-size=20）
     * @return 推荐流条目列表
     */
    List<FeedItemVO> feed(Long userId, int pageNo, int pageSize);
}
