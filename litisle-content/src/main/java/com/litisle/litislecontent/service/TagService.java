package com.litisle.litislecontent.service;


import com.litisle.litislecontent.domain.vo.TagVO;

import java.util.List;

/**
 * 标签域服务接口（前缀搜索/热门/挂载）——REST 由 TagController 调用：
 *
 * 【核心机制（§6.7 + [6.2]）】
 * - 写路径：输入触发前缀搜索已有标签池（防碎片化），确无命中才新建——新建走 DFA 闸门
 *   （同步 dfaFacade.check(BIO, name)，任一命中即拒挂抛 SENSITIVE_WORD_DETECTED；uk_name 并发重查幂等）
 * - 数量上限：每篇 ≤ tag.max-per-article(5)；uk_article_tag 冲突幂等跳过；名称 ≤64 字与词库词条同口径
 * - use_count：挂载/摘除同事务原子增减为主 + TagCountReconcileJob 定时对账兑底；支撑热门 TOP N
 * - 热门 TOP N：Redis 短缓存（TTL 基准 300s ±10% 抖动）
 * - 方案B：status=2 允许增删标签（标签不影响 content_hash），新增仍过 DFA
 */
public interface TagService {

    /**
     * 前缀搜索已有标签池（防碎片化；输入联想用）
     *
     * @param keyword 关键词前缀
     * @return 标签列表
     */
    List<TagVO> searchByPrefix(String keyword);

    /**
     * 热门标签 TOP N（按 use_count 倒序；封禁/已删标签不聚合展示）
     */
    List<TagVO> hotTags();

    /**
     * 挂载标签（≤5 超限拒绝；新建过 DFA 闸门；冲突幂等跳过）
     *
     * @param articleId  文章ID
     * @param tagNames   标签名列表
     * @param operatorId 当前登录用户ID（owner 校验）
     */
    void attachTags(Long articleId, List<String> tagNames, Long operatorId);

    /**
     * 取消挂载标签（owner）
     *
     * @param articleId  文章ID
     * @param tagIds     待摘除标签ID列表
     * @param operatorId 操作人ID
     * @return 实际摘除的标签数量
     */
    int detachTags(Long articleId, List<Long> tagIds, Long operatorId);
}