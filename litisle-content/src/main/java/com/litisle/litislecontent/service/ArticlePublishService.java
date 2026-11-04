package com.litisle.litislecontent.service;


import com.litisle.litisleapi.dto.content.ContentPreviewCheckDTO;
import com.litisle.litisleapi.dto.content.ContentPreviewCheckVO;
import com.litisle.litislecontent.domain.vo.ArticleBatchResultVO;
import com.litisle.litislecontent.enums.VisibilityEnum;

import java.util.List;

/**
 * 发布编排域服务接口（发布/批量/预检/密码校验）——REST 由 ArticlePublishController 调用：
 *
 * 发布编排同步段（口径B，只做 A1→A3，不同步等审，审核时长不绑架发布 RT）：
 *   A1 校验（trim 非空/去标签可见文本非空/标题≤100/正文配图不同时空）
 *   A2 免审直落判定（last-hash + content_hash 命中 → CAS 0→2，免文不免图）
 *   A3 未命中 CAS 0→1 → afterCommit 发单条 content.submitted（事件体不含正文）
 *
 * List<ArticleBatchResultVO> publishBatch(List<Long> articleIds, Long operatorId) // 批量发布（≤20，逐篇独立事务+逐篇回执）
 * void batchVisibility(List<Long> articleIds, VisibilityEnum target, Long operatorId) // 批量改可见性（逐篇CAS+逐篇发事件）
 * ContentPreviewCheckVO previewCheck(ContentPreviewCheckDTO dto, Long userId) // 发布前预检（仅 {hit,risk} 不回吐命中词）
 */
public interface ArticlePublishService {

    /**
     * 批量发布（单批 ≤ publish.batch-max，超限拒绝；逐篇独立事务，失败不回滚已成功篇目；
     * 已发布篇目 CAS 不满足自动跳过——幂等；返回逐篇回执 {articleId, success, errorCode}）
     *
     * @param articleIds 文章ID列表
     * @param operatorId 当前登录用户ID
     * @return 逐篇发布结果回执列表
     */
    List<ArticleBatchResultVO> publishBatch(List<Long> articleIds, Long operatorId);

    /**
     * 批量改可见性（仅 owner；逐篇 CAS + 每篇独立发 content.unpublished/published 事件；
     * 转公开(→1)不重审：content_hash 校验内容未变 + 方案B 禁改正文，CAS 限定 status=2）
     *
     * @param articleIds 文章ID列表
     * @param target     目标可见性（IntDictEnum，入参 name/code/desc 三值兼容）
     * @param operatorId 当前登录用户ID
     */
    void batchVisibility(List<Long> articleIds, VisibilityEnum target, Long operatorId);

    /**
     * 发布前预检（@RateLimiter(USER) 限频；仅返回 {hit, risk} 不回吐命中词；不替代发布闸门）
     *
     * @param dto    预检入参（标题/正文HTML，DTO 来自 litisle-api 共享）
     * @param userId 当前用户ID（限频键）
     * @return 预检结果 {hit, risk}
     */
    ContentPreviewCheckVO previewCheck(ContentPreviewCheckDTO dto, Long userId);

    /**
     * 定时发布到点扫描（供 ArticleScheduledPublishJob 调用）：
     * 扫描到点的草稿态文章（status=0 且 scheduled_publish_time≤now 且未软删），
     * 逐篇复用 publishBatch 走"归属校验→状态机→CAS 0→1→送审事件"既有链路；
     * 逐篇独立事务，单篇失败不影响其他篇。
     * 发布成功或失败均清空 scheduled_publish_time（失败不静默：warn 日志留痕，
     * 文章停留草稿态作者在"我的文章"可见，同时防同一篇被反复重扫）。
     *
     * @return 本轮实际完成发布（送审）的篇数
     */
    int publishDueScheduled();
}