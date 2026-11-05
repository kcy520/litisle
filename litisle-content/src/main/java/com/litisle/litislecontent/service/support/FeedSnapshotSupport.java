package com.litisle.litislecontent.service.support;

import com.litisle.commonredis.util.RedisUtil;
import com.litisle.litislecontent.config.ContentProperties;
import com.litisle.litislecontent.constant.FeedConstant;
import com.litisle.litislecontent.domain.dto.FeedQuery;
import com.litisle.litislecontent.domain.dto.FeedSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 推荐流快照存储（批次十 10.3：快照读写 + 当日曝光去重 + 会话种子 + Redis 可用性探测）
 *
 * <p>设计要点：
 * <ul>
 *   <li>快照键含「流类型 + 身份 + 分区筛选 + 快照代次」，同一用户不同筛选条件互不串池；</li>
 *   <li>曝光键含「日期 + 身份」，保证当日硬去重（跨日自然失效，TTL 2 天兜底残留）；</li>
 *   <li>种子键按身份保存，同一会话内翻页顺序稳定，跨会话重新随机；</li>
 *   <li>Redis 不可用时由调用方降级为「实时计算一页」，本类只负责探测与抛回结果。</li>
 * </ul>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedSnapshotSupport {

    /** 曝光键日期格式（yyyyMMdd，直接拼进 Redis key） */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /** 快照索引解析失败时的兜底 TTL（秒）：索引异常不影响主流程，按默认 30 分钟重建 */
    private static final long INDEX_FALLBACK_TTL_SECONDS = 1800L;

    /** Redis 工具（快照/曝光/种子读写） */
    private final RedisUtil redisUtil;

    /** 推荐流配置（快照 TTL/曝光 TTL/活动快照数） */
    private final ContentProperties contentProperties;

    /**
     * 探测 Redis 是否可用。
     *
     * <p>不能复用 {@link RedisUtil#hasKey}：该封装内部吞异常并返回 false，
     * 无法区分「键不存在」与「连接失败」。这里直接走 RedisTemplate，异常即判定不可用，
     * 调用方据此进入「实时计算一页」的降级路径。</p>
     *
     * @return true=Redis 可读写；false=Redis 不可用
     */
    public boolean isRedisAvailable() {
        try {
            redisUtil.getRedisTemplate().hasKey(FeedConstant.SNAPSHOT_PROBE_KEY);
            return true;
        } catch (Exception exception) {
            log.warn("推荐流 Redis 探测失败（进入降级路径）：{}", exception.getMessage());
            return false;
        }
    }

    /**
     * 生成身份标识（登录用 u+userId、匿名用 a+anonId），用于快照/曝光/种子键隔离
     *
     * @param query 查询入参
     * @return 身份标识
     */
    public String resolveIdentity(FeedQuery query) {
        if (query.getUserId() != null) {
            return FeedConstant.IDENTITY_USER_PREFIX + query.getUserId();
        }
        return FeedConstant.IDENTITY_ANON_PREFIX + query.getAnonId();
    }

    /**
     * 生成快照作用域（流类型 + 身份，文章流带分区筛选后缀）
     *
     * @param streamType 流类型（article/book）
     * @param identity   身份标识
     * @param zoneCode   分区筛选码（null 表示不筛选，仅文章流使用）
     * @return 作用域字符串
     */
    public String resolveScope(String streamType, String identity, Integer zoneCode) {
        // 分区筛选参与键隔离：同一用户切换分区视为两条独立流，避免共用一份快照产生错位
        if (zoneCode == null) {
            return streamType + ":" + identity;
        }
        return streamType + ":" + identity + ":zone" + zoneCode;
    }

    /**
     * 读取快照
     *
     * @param scope      快照作用域
     * @param snapshotId 快照代次
     * @return 快照对象；不存在/过期/反序列化失败返回 null（调用方按快照失效处理）
     */
    public FeedSnapshot readSnapshot(String scope, String snapshotId) {
        // 快照代次为空视为没有快照
        if (!StringUtils.hasText(snapshotId)) {
            return null;
        }
        String key = buildSnapshotKey(scope, snapshotId);
        try {
            Object value = redisUtil.get(key);
            // 非快照类型或条目缺失（缓存损坏/类结构变更）一律按失效处理，由调用方重建
            if (value instanceof FeedSnapshot snapshot && snapshot.getItems() != null) {
                return snapshot;
            }
            return null;
        } catch (Exception exception) {
            // 反序列化失败（缓存损坏/类结构变更）：按快照失效处理，由调用方重建
            log.warn("推荐流快照读取失败（按失效处理）：key={}", key, exception);
            return null;
        }
    }

    /**
     * 写入快照并登记到活动快照索引
     *
     * @param scope   快照作用域
     * @param snapshot 快照对象
     */
    public void writeSnapshot(String scope, FeedSnapshot snapshot) {
        ContentProperties.Stream stream = contentProperties.getFeed().getStream();
        String key = buildSnapshotKey(scope, snapshot.getSnapshotId());
        redisUtil.set(key, snapshot, stream.getSnapshotTtlSeconds(), TimeUnit.SECONDS);
        // 登记索引：超过 maxActiveSnapshots 时删除最旧的快照键，防翻页风暴堆积
        registerActiveSnapshot(scope, snapshot.getSnapshotId(), stream);
    }

    /**
     * 生成下一个快照代次（Redis INCR，单调递增）
     *
     * @param scope 快照作用域
     * @return 新代次字符串；Redis 异常（increment 返回 0）时返回 null
     */
    public String nextSnapshotId(String scope) {
        String key = FeedConstant.SNAPSHOT_GEN_PREFIX + scope;
        Long generation = redisUtil.increment(key, 1);
        // Redis 封装异常时返回 0，视为本轮生成失败，由调用方走降级
        if (generation == null || generation <= 0) {
            return null;
        }
        // 计数器 TTL 与快照同窗：长期不刷的用户代次自动归零，不占存储
        redisUtil.expire(key, contentProperties.getFeed().getStream().getSnapshotTtlSeconds(), TimeUnit.SECONDS);
        return String.valueOf(generation);
    }

    /**
     * 读取或生成会话种子
     *
     * <p>同一身份在快照 TTL 窗口内复用同一 seed（同一会话翻页稳定）；
     * 跨窗口重新生成（跨会话顺序可变）。随机只在内存排序时使用，不下发 SQL。</p>
     *
     * @param scope 快照作用域
     * @return 会话种子
     */
    public long resolveSessionSeed(String scope) {
        String key = FeedConstant.SNAPSHOT_SEED_PREFIX + scope;
        Object cached = redisUtil.get(key);
        if (cached != null) {
            try {
                return Long.parseLong(String.valueOf(cached));
            } catch (NumberFormatException exception) {
                // 种子值脏数据：重新生成覆盖，不阻断建池
                log.warn("推荐流种子值非法（重新生成）：key={}, value={}", key, cached);
            }
        }
        long seed = ThreadLocalRandom.current().nextLong();
        redisUtil.set(key, String.valueOf(seed),
                contentProperties.getFeed().getStream().getSnapshotTtlSeconds(), TimeUnit.SECONDS);
        return seed;
    }

    /**
     * 读取当日已曝光的对象 ID
     *
     * @param streamType 流类型（article/book；文章与书籍 ID 各自独立，避免同号互斥）
     * @param identity   身份标识
     * @return 已曝光 ID 集合；Redis 异常/无记录返回空集合
     */
    public Set<Long> loadTodayExposed(String streamType, String identity) {
        String key = buildExposedKey(streamType, identity);
        Set<String> members = redisUtil.sMembers(key);
        // 转 Long：Set 成员以字符串写入，兼容不同序列化器回读类型差异
        Set<Long> exposedIds = new HashSet<>(members.size());
        for (String member : members) {
            try {
                exposedIds.add(Long.parseLong(member));
            } catch (NumberFormatException exception) {
                log.warn("推荐流曝光记录非法（忽略该成员）：key={}, member={}", key, member);
            }
        }
        return exposedIds;
    }

    /**
     * 标记本页已曝光（写入当日曝光 Set）
     *
     * @param streamType 流类型（article/book）
     * @param identity   身份标识
     * @param ids        本次返回的对象 ID（空集合直接跳过，避免写空键）
     */
    public void markExposed(String streamType, String identity, Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String key = buildExposedKey(streamType, identity);
        // 成员统一转字符串，与 loadTodayExposed 的解析口径保持一致
        Object[] members = ids.stream().map(String::valueOf).toArray();
        redisUtil.sAdd(key, members);
        redisUtil.expire(key, contentProperties.getFeed().getStream().getExposureTtlSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 构建快照数据键
     *
     * @param scope      快照作用域
     * @param snapshotId 快照代次
     * @return Redis key
     */
    private String buildSnapshotKey(String scope, String snapshotId) {
        return FeedConstant.SNAPSHOT_KEY_PREFIX + scope + ":" + snapshotId;
    }

    /**
     * 构建当日曝光键（日期 + 流类型 + 身份）
     *
     * <p>带流类型的原因：文章与书籍 ID 同为自增 bigint，不带流类型会互相误判为已曝光。</p>
     *
     * @param streamType 流类型
     * @param identity   身份标识
     * @return Redis key
     */
    private String buildExposedKey(String streamType, String identity) {
        return FeedConstant.EXPOSED_KEY_PREFIX + streamType + ":" + identity + ":" + LocalDate.now().format(DATE_FORMATTER);
    }

    /**
     * 登记活动快照并裁剪超出配额的旧快照
     *
     * <p>索引以 JSON 字符串存最近若干代次（最新在前）；超过 maxActiveSnapshots 时
     * 删除最旧代次的数据键并从索引移除（RedisUtil 无 LTRIM，用整体重写替代）。</p>
     *
     * @param scope      快照作用域
     * @param snapshotId 新快照代次
     * @param stream     推荐流配置
     */
    private void registerActiveSnapshot(String scope, String snapshotId, ContentProperties.Stream stream) {
        String indexKey = FeedConstant.SNAPSHOT_INDEX_PREFIX + scope;
        // 读取现有索引（不存在/脏数据按空列表处理）
        List<String> activeIds = readActiveSnapshotIds(indexKey);
        // 新代次插入头部（最新在前）
        activeIds.add(0, snapshotId);
        // 裁剪超出配额的旧代次：删除其数据键
        while (activeIds.size() > Math.max(stream.getMaxActiveSnapshots(), 1)) {
            String staleId = activeIds.remove(activeIds.size() - 1);
            redisUtil.delete(buildSnapshotKey(scope, staleId));
        }
        long ttlSeconds = stream.getSnapshotTtlSeconds() > 0 ? stream.getSnapshotTtlSeconds() : INDEX_FALLBACK_TTL_SECONDS;
        redisUtil.set(indexKey, activeIds, ttlSeconds, TimeUnit.SECONDS);
    }

    /**
     * 读取活动快照代次索引
     *
     * @param indexKey 索引键
     * @return 代次列表（最新在前）；异常/脏数据返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<String> readActiveSnapshotIds(String indexKey) {
        Object value = redisUtil.get(indexKey);
        if (value instanceof List<?> rawList) {
            List<String> ids = new ArrayList<>(rawList.size());
            for (Object item : rawList) {
                if (item != null) {
                    ids.add(String.valueOf(item));
                }
            }
            return ids;
        }
        // 非 List（首次写入/旧格式脏值）：返回可变空列表，调用方可直接插入新代次
        return new ArrayList<>();
    }
}
