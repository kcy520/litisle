package com.litisle.litislesearch.service;

import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.vo.search.SearchResultVO;

/**
 * 热点查询缓存服务（批次四 4.1 + 批次三 3.2 遗留「缓存失效」接入）。
 *
 * 【职责】检索结果的 Redis 缓存读写与失效：
 * - 读：命中直接返回，Redis 异常时降级为查 ES（fail-open，不阻塞检索主链路）
 * - 写：仅缓存常规 ES 命中结果；兜底/降级结果禁止写入（防把兜底当真命中）
 * - 失效：索引变更（发布/下架/元数据更新）与热度刷写完成后全量清理 search:hotquery:* 键
 *
 * 【失效降频】发布/元数据/书籍/用户等非隐私事件改为 {@link #markDirty()} 打脏标记，
 * 由定时任务按配置间隔调用 {@link #evictIfDirty()} 合并执行一次 evictAll；
 * 下架事件仍必须直接调用 {@link #evictAll()}（隐私红线，不得进入合并窗口）。
 *
 * @author litisle
 */
public interface HotQueryCacheService {

    /**
     * 读取缓存结果。
     *
     * @param cacheKey 缓存键（由 {@link #buildKey} 生成）
     * @return 缓存命中结果；未命中、缓存关闭或 Redis/反序列化异常时返回 null（调用方继续查 ES）
     */
    SearchResultVO read(String cacheKey);

    /**
     * 写入缓存结果（仅 source=ES 的常规命中结果生效）。
     *
     * @param cacheKey 缓存键
     * @param result   检索结果（兜底/降级结果会被忽略）
     */
    void write(String cacheKey, SearchResultVO result);

    /**
     * 构造热点查询缓存键：search:hotquery:{type}:{sort}:{pageNum}:{pageSize}:{查询指纹}。
     *
     * @param request    原始检索请求（取 tag/category/zone/时间范围参与指纹）
     * @param typeCode   检索类型 code（article/book/user）
     * @param sortCode   排序 code（related/newest/heat）
     * @param pageNum    页码
     * @param pageSize   每页条数
     * @param keyword    已归一化的查询词（未转义，保证相同查询共享缓存）
     * @return Redis 缓存键
     */
    String buildKey(SearchRequestDTO request, String typeCode, String sortCode,
                    int pageNum, int pageSize, String keyword);

    /**
     * 全量失效热点查询缓存（SCAN 匹配 search:hotquery:* 后分批删除）。
     *
     * 【异常】Redis 故障抛 IllegalStateException（可恢复故障）：
     * 索引下架等场景需由 MQ 重试保证缓存最终被清理（隐私红线，不能静默放过）
     */
    void evictAll();

    /**
     * 标记热点查询缓存「待失效」（只写一个带短 TTL 的 Redis 脏标记，不执行 SCAN）。
     *
     * <p>适用发布/元数据更新/书籍变更/用户资料更新等可合并事件；下架隐私红线不得调用本方法。</p>
     *
     * 【异常】Redis 故障抛 IllegalStateException（可恢复故障），交给 MQ 延迟重试，
     * 不得静默吞掉，否则索引变更后的缓存失效可能永久丢失。
     */
    void markDirty();

    /**
     * 若存在脏标记则执行一次全量失效（仅供定时合并任务调用）。
     *
     * <p>执行顺序：先删除脏标记再 evictAll，保证清理期间新产生的标记能保留到下一轮；
     * evictAll 失败时会尽力重新打标，避免「标记已删但没清成功」导致永久漏失效。</p>
     *
     * @return true=本轮实际执行了失效；false=无脏标记或缓存开关关闭（无需清理）
     * @throws IllegalStateException Redis/失效异常（由定时任务记日志与指标，下轮继续）
     */
    boolean evictIfDirty();
}
