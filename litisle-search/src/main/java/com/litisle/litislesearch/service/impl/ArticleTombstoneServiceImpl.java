package com.litisle.litislesearch.service.impl;

import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchRedisConstant;
import com.litisle.litislesearch.service.ArticleTombstoneService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 文章下架墓碑实现（批次九 P0-D）。
 *
 * <p>键格式：{@link SearchRedisConstant#ARTICLE_TOMBSTONE_KEY_PREFIX} + articleId；
 * TTL 取 `litisle.search.index.tombstone-ttl`（默认 10 分钟），
 * 覆盖「5 次 × 30 秒」的最大延迟重投时长并留处理余量。</p>
 *
 * <p>为什么不直接依赖串行消费：发布/下架是不同队列、不同监听器，串行也只能保证队列内有序，
 * 跨队列仍可能「发布回查早于下架提交、发布写入晚于下架删除」，导致已下架文章复活；
 * 墓碑让写路径在落地前再确认一次权威状态，属隐私红线兜底。</p>
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleTombstoneServiceImpl implements ArticleTombstoneService {

    /** Redis 客户端（墓碑键读写；与热点缓存共用连接工厂） */
    private final StringRedisTemplate stringRedisTemplate;

    /** search 配置（读取 index.tombstone-ttl，避免硬编码 TTL） */
    private final SearchProperties properties;


    /**
     * 写文章下架墓碑（SET key value EX ttl）。
     *
     * @param articleId 文章 ID
     */
    @Override
    public void markUnpublished(Long articleId) {
        String key = buildKey(articleId);
        try {
            // value 无业务含义，仅表示「窗口内下架过」，靠 TTL 自动消失
            stringRedisTemplate.opsForValue().set(key, "1", properties.getIndex().getTombstoneTtl());
        } catch (Exception e) {
            // 墓碑写失败：下架事件已删除索引，但隐私兜底未建立 → 抛可恢复异常触发重投（删除幂等可重放）
            log.error("[索引同步] 下架墓碑写入失败（可重试）：articleId={}, key={}", articleId, key, e);
            throw new IllegalStateException("写入文章下架墓碑失败（可重试），articleId=" + articleId, e);
        }
    }

    /**
     * 判断墓碑是否存在。
     *
     * @param articleId 文章 ID
     * @return true=窗口内下架过，写路径需重新门控
     */
    @Override
    public boolean exists(Long articleId) {
        String key = buildKey(articleId);
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        } catch (Exception e) {
            // 查询失败宁可重试也不放行：放行可能把已下架文章写回索引（隐私红线）
            log.error("[索引同步] 下架墓碑查询失败（可重试）：articleId={}, key={}", articleId, key, e);
            throw new IllegalStateException("查询文章下架墓碑失败（可重试），articleId=" + articleId, e);
        }
    }


    /**
     * 拼接墓碑键（前缀常量 + articleId）。
     *
     * @param articleId 文章 ID
     * @return Redis 键
     */
    private String buildKey(Long articleId) {

        return SearchRedisConstant.ARTICLE_TOMBSTONE_KEY_PREFIX + articleId;
    }
}
