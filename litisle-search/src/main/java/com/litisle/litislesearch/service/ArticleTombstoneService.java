package com.litisle.litislesearch.service;

/**
 * 文章下架墓碑服务：用短 TTL 的 Redis 标记记录「刚刚下架的文章」，
 * 供发布/元数据写路径在写入前强制重新门控，防止跨队列竞态把已下架文章写回索引。
 *
 * <p>只对文章生效（书籍/用户没有下架语义）。所有 Redis 故障都按可恢复异常抛出，
 * 由消费端延迟重试，不得静默吞掉，否则隐私兜底会失效。</p>
 *
 * @author litisle
 */
public interface ArticleTombstoneService {

    /**
     * 写文章下架墓碑（删除索引成功后调用）。
     *
     * @param articleId 文章 ID
     * @throws IllegalStateException Redis 写入失败（可重试，交 MQ 延迟重投）
     */
    void markUnpublished(Long articleId);

    /**
     * 判断文章是否处于下架墓碑有效期内。
     *
     * @param articleId 文章 ID
     * @return true=命中墓碑（写路径必须重新回查门控）
     * @throws IllegalStateException Redis 查询失败（可重试；宁可重试也不放行写入）
     */
    boolean exists(Long articleId);
}
