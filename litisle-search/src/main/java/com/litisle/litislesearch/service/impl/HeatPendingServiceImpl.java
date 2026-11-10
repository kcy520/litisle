package com.litisle.litislesearch.service.impl;

import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchRedisConstant;
import com.litisle.litislesearch.service.HeatPendingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 互动热度待刷新键服务实现（批次三 3.1 累加 + 3.2 取走/回灌）。
 *
 * 【Redis 结构】key = search:heat:pending:{articleId}，Hash 字段：
 * - view_cnt：浏览量绝对值（HSET 覆盖写，可重放）
 * - like_delta：窗口内点赞增量（HINCRBY 累加）
 * - comment_delta：窗口内评论增量（HINCRBY 累加）
 *
 * 【TTL】每次写入刷新 pending-key-ttl（默认 2h ≥ 2 个刷写周期）：
 * 正常路径由批次三 3.2 的刷写任务原子取走，TTL 仅在刷写长期失败时兜底过期，
 * 防脏键永久堆积。
 *
 * 【异常】Redis 访问失败统一包装为 IllegalStateException（非 BizException），
 * 由消费端触发延迟重试，避免消息被当作业务失败直接丢弃导致热度漂移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeatPendingServiceImpl implements HeatPendingService {

    /** 每次点赞/评论事件的计数步长（一条事件代表一次互动，固定为 1） */
    private static final long COUNTER_STEP = 1L;

    /**
     * 原子取走脚本（GETDEL 语义）：Hash 类型的键无法使用 Redis 原生 GETDEL，
     * 用 Lua 在同一次原子执行内 HGETALL + DEL，并返回字段/值交替数组
     */
    private static final String LUA_TAKE_HASH = "local v = redis.call('HGETALL', KEYS[1]) "
            + "redis.call('DEL', KEYS[1]) return v";

    /** 取走脚本对象（结果类型为 List，字段名与值交替排列；脚本常量在类加载时构建一次） */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> TAKE_HASH_SCRIPT =
            new DefaultRedisScript<>(LUA_TAKE_HASH, List.class);

    /** Redis 字符串模板（Hash 自增/覆盖写 + TTL 刷新 + 原子取走） */
    private final StringRedisTemplate stringRedisTemplate;

    /** search 配置（pending 键 TTL / 扫描批量数取 heat.flush.*，外置可调） */
    private final SearchProperties properties;

    /**
     * 点赞增量 +1。
     *
     * @param articleId 被点赞文章 ID
     */
    @Override
    public void incrementLike(Long articleId) {
        increment(articleId, SearchRedisConstant.HEAT_PENDING_FIELD_LIKE, COUNTER_STEP);
    }

    /**
     * 按事件 delta 累加点赞增量（批次十一 11.14：点赞 +1、取消点赞 -1）。
     *
     * @param articleId 文章 ID
     * @param delta     点赞增量（0 直接跳过，避免无意义写 Redis）
     */
    @Override
    public void applyLikeDelta(Long articleId, long delta) {
        if (delta == 0) {
            return;
        }
        increment(articleId, SearchRedisConstant.HEAT_PENDING_FIELD_LIKE, delta);
    }

    /**
     * 评论增量 +1。
     *
     * @param articleId 被评论文章 ID
     */
    @Override
    public void incrementComment(Long articleId) {
        increment(articleId, SearchRedisConstant.HEAT_PENDING_FIELD_COMMENT, COUNTER_STEP);
    }

    /**
     * 覆盖写入浏览量绝对值（同一 Hash 字段 HSET，重复消费不会累加出错）。
     *
     * @param articleId 文章 ID
     * @param viewCount 落库后的权威浏览量
     */
    @Override
    public void overwriteViewCount(Long articleId, Long viewCount) {
        String key = buildKey(articleId);
        try {
            // HSET 覆盖语义：写入 content 侧落库后的最新绝对值，事件重放结果一致
            stringRedisTemplate.opsForHash().put(key, SearchRedisConstant.HEAT_PENDING_FIELD_VIEW, String.valueOf(viewCount));
            refreshTtl(key);
        } catch (Exception e) {
            // 包装为非业务异常，交由消费端延迟重试（Redis 抖动属可恢复故障）
            throw new IllegalStateException("写入浏览量热度待刷新键失败（可重试），articleId=" + articleId, e);
        }
    }

    /**
     * 扫描全部热度待刷新键（SCAN 游标遍历，count 取刷写批量数降低往返次数）。
     *
     * @return 待刷新键列表
     */
    @Override
    public List<String> scanPendingKeys() {
        // 扫描匹配模式：固定前缀 + 通配符（前缀为服务端常量，无用户可控原串）
        String pattern = SearchRedisConstant.HEAT_PENDING_KEY_PREFIX + "*";
        // 每次 SCAN 建议返回的元素个数，取刷写批量数，避免过多小批次往返
        int scanCount = Math.max(1, properties.getHeat().getFlush().getBatchSize());
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(scanCount).build())) {
            cursor.forEachRemaining(keys::add);
        } catch (Exception e) {
            // 扫描失败属可恢复故障：本轮跳过，等下一窗口重试（pending 键有 TTL 兜底不会丢）
            throw new IllegalStateException("扫描热度待刷新键失败（可重试）", e);
        }
        return keys;
    }

    /**
     * 原子取走单个待刷新键并返回 Hash 内容（Lua HGETALL+DEL，GETDEL 语义）。
     *
     * @param key 待刷新键
     * @return Hash 字段名→值（键不存在时返回空 Map）
     */
    @Override
    public Map<String, String> takePending(String key) {
        try {
            // 执行原子脚本：返回字段/值交替的扁平数组，偶数下标为字段名，奇数下标为值
            List<?> flat = stringRedisTemplate.execute(TAKE_HASH_SCRIPT, Collections.singletonList(key));
            if (flat == null || flat.isEmpty()) {
                // 键已不存在（可能被上一轮取走或 TTL 过期）：按空处理，调用方跳过
                return Collections.emptyMap();
            }
            Map<String, String> result = new HashMap<>();
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                result.put(String.valueOf(flat.get(i)), String.valueOf(flat.get(i + 1)));
            }
            return result;
        } catch (Exception e) {
            // 取走失败：本轮跳过该键（键未被删除，数据仍在，下一轮仍可刷写）
            throw new IllegalStateException("原子取走热度待刷新键失败（可重试），key=" + key, e);
        }
    }

    /**
     * 刷写失败回灌（点赞/评论按增量追加，浏览量存在则保留）。
     *
     * @param articleId    文章 ID
     * @param likeDelta    点赞增量
     * @param commentDelta 评论增量
     * @param viewCount    浏览量绝对值
     */
    @Override
    public void mergeBack(Long articleId, long likeDelta, long commentDelta, Long viewCount) {
        String key = buildKey(articleId);
        try {
            // 点赞/评论：失败窗口内可能已有新事件写入，按增量追加而非覆盖，避免丢掉新到达的互动
            if (likeDelta != 0) {
                stringRedisTemplate.opsForHash().increment(key, SearchRedisConstant.HEAT_PENDING_FIELD_LIKE, likeDelta);
            }
            if (commentDelta != 0) {
                stringRedisTemplate.opsForHash().increment(key, SearchRedisConstant.HEAT_PENDING_FIELD_COMMENT, commentDelta);
            }
            // 浏览量：仅当字段不存在时写入（失败值通常旧于新事件值，不能用旧值覆盖新值）
            if (viewCount != null) {
                stringRedisTemplate.opsForHash().putIfAbsent(key, SearchRedisConstant.HEAT_PENDING_FIELD_VIEW,
                        String.valueOf(viewCount));
            }
            refreshTtl(key);
        } catch (Exception e) {
            // 回灌失败只告警不抛：任务本身是兜底链路，不能让回灌异常中断整轮刷写
            log.error("[热度刷写] 增量回灌失败，需人工关注：articleId={}, likeDelta={}, commentDelta={}, viewCount={}",
                    articleId, likeDelta, commentDelta, viewCount, e);
        }
    }

    /**
     * 计数类字段自增（点赞/评论共用，支持正负步长）。
     *
     * @param articleId 文章 ID
     * @param field     Hash 字段名（like_delta / comment_delta）
     * @param step      增量步长（点赞 +1、取消点赞 -1、评论 +1）
     */
    private void increment(Long articleId, String field, long step) {
        String key = buildKey(articleId);
        try {
            // HINCRBY：字段不存在时按 0 起算，天然支持首次累加；负数步长即递减
            stringRedisTemplate.opsForHash().increment(key, field, step);
            refreshTtl(key);
        } catch (Exception e) {
            // 同上：可恢复故障交由消费端重试，绝不静默吞掉（否则热度只少不多）
            throw new IllegalStateException("累加热度待刷新键失败（可重试），articleId=" + articleId + ", field=" + field, e);
        }
    }

    /**
     * 构造待刷新键（前缀常量 + 文章 ID，无用户可控原串）。
     *
     * @param articleId 文章 ID
     * @return Redis 键
     */
    private String buildKey(Long articleId) {
        return SearchRedisConstant.HEAT_PENDING_KEY_PREFIX + articleId;
    }

    /**
     * 刷新 pending 键 TTL（兜底过期，正常路径由刷写任务 GETDEL 取走）。
     *
     * @param key 待刷新键
     */
    private void refreshTtl(String key) {
        stringRedisTemplate.expire(key, properties.getHeat().getFlush().getPendingKeyTtl());
    }
}
