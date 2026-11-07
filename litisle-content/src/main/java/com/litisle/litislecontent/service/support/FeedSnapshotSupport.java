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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 推荐流快照存储（批次十 10.3：快照读写 + 当日曝光去重 + 会话种子 + Redis 可用性探测）
 *
 * <p>设计要点：
 * <ul>
 *   <li>快照键含「流类型 + 身份 + 分区筛选 + 快照代次」，同一用户不同筛选条件互不串池；</li>
 *   <li>曝光键含「日期 + 流类型 + 身份」，保证当日硬去重（跨日自然失效，TTL 2 天兜底残留）；
 *       流类型参与键隔离，避免文章 ID 与书籍 ID 同为 bigint 时跨流误去重；</li>
 *   <li>种子键按身份保存，同一会话内翻页顺序稳定，跨会话重新随机；</li>
 *   <li>Redis 不可用时由调用方降级为「实时计算一页」，本类只负责探测与抛回结果；</li>
 *   <li>last-good（10.6）：每次成功写入非空快照时记录内存副本与 Redis 指针，
 *       降级时优先返回它，避免直接从快照退到实时计算。</li>
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

    /** last-good 内存缓存条目上限（按作用域计，约覆盖 200 个活跃身份，超出按最久未用淘汰） */
    private static final int LAST_GOOD_CACHE_MAX = 200;

    /** Redis 工具（快照/曝光/种子读写） */
    private final RedisUtil redisUtil;

    /**
     * last-good 快照内存副本（作用域 → 最近一次成功写入的快照）
     *
     * <p>为什么需要内存副本：Redis 不可用时只能靠本实例已生成过的快照兜底；
     * 用访问序 LinkedHashMap + 上限淘汰，防止长时间运行下身份作用域无界增长。</p>
     */
    private final Map<String, LastGoodSnapshot> lastGoodCache = Collections.synchronizedMap(
            new LinkedHashMap<String, LastGoodSnapshot>(16, 0.75f, true) {
                /** 超出上限时淘汰最久未访问的作用域 */
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LastGoodSnapshot> eldest) {
                    return size() > LAST_GOOD_CACHE_MAX;
                }
            });

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
     * 读取上一代成功快照（批次十 10.6：降级兜底优先返回它，不直接掉实时计算）
     *
     * <p>两级来源，按可用性依次尝试：</p>
     * <ol>
     *   <li>本实例内存副本：Redis 不可用时唯一可用来源；副本超过快照 TTL 视为过期；</li>
     *   <li>Redis 指针（{@code content:feed:snapshot:last:{scope}} → 快照代次）：
     *       Redis 正常时覆盖「本实例重启 / 多实例部署」场景，指针过期或快照已被裁剪则返回 null。</li>
     * </ol>
     *
     * @param scope 快照作用域
     * @return 上一代成功快照；无可用副本返回 null（调用方继续走实时计算降级）
     */
    public FeedSnapshot readLastGood(String scope) {
        // 来源1：本实例内存副本（Redis 不可用时也一定能读）
        LastGoodSnapshot cached = lastGoodCache.get(scope);
        if (cached != null && !isLastGoodExpired(cached.getSavedAtMillis())) {
            return cached.getSnapshot();
        }
        // 来源2：Redis 指针回读（Redis 不可用时 RedisUtil 吞异常返回 null）
        Object pointer = redisUtil.get(FeedConstant.SNAPSHOT_LAST_PREFIX + scope);
        if (pointer == null) {
            return null;
        }
        FeedSnapshot snapshot = readSnapshot(scope, String.valueOf(pointer));
        // 空池快照不作为兜底内容（没有可曝光条目）
        if (snapshot == null || snapshot.getItems() == null || snapshot.getItems().isEmpty()) {
            return null;
        }
        return snapshot;
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
        // 记录 last-good：非空快照才更新（空池快照没有兜底价值），供降级路径优先返回上一代成功内容
        saveLastGood(scope, snapshot);
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
     * 判断某内容今天是否已对该身份曝光（批次十一 11.6 点击归因前置校验）
     *
     * <p>作用：只有「确实曝光过」的内容才允许累计点击，避免伪造 from=feed 参数造出
     * 无曝光分母的点击行，从源头保证 CTR 不超过 1。Redis 不可用时 sIsMember 内部吞异常返回
     * false，点击按不计数处理（fail-safe，只影响分子不影响主流程）。</p>
     *
     * @param streamType 流类型（article/book）
     * @param identity   身份标识（u{userId} / a{anonId}）
     * @param targetId   内容 ID
     * @return true=今天已曝光，可计点击；false=未曝光或 Redis 不可用
     */
    public boolean isExposedToday(String streamType, String identity, Long targetId) {
        return redisUtil.sIsMember(buildExposedKey(streamType, identity), String.valueOf(targetId));
    }

    /**
     * 标记某内容今天已被该身份点击（批次十一 11.6 点击去重）
     *
     * <p>用 SADD 返回值判断是否首次点击：返回 >0 表示本次是新增成员（首次点击，可累计）；
     * 返回 0 可能是「今天已点过」也可能是 Redis 异常（封装吞异常返回 0），两种情况都按不计点击处理，
     * 保证 CTR 分子不会被重复刷新放大。TTL 与曝光 Set 同窗（默认 2 天），跨日自然失效。</p>
     *
     * @param streamType 流类型（article/book）
     * @param identity   身份标识（u{userId} / a{anonId}）
     * @param targetId   内容 ID
     * @return true=首次点击（调用方累计 click_count）；false=重复点击或 Redis 不可用
     */
    public boolean markClicked(String streamType, String identity, Long targetId) {
        String key = buildClickedKey(streamType, identity);
        Long added = redisUtil.sAdd(key, String.valueOf(targetId));
        // 首次点击才续期：重复点击不刷新 TTL，避免活跃用户把键永久留住
        if (added != null && added > 0) {
            redisUtil.expire(key, contentProperties.getFeed().getStream().getExposureTtlSeconds(), TimeUnit.SECONDS);
            return true;
        }
        return false;
    }

    /**
     * 记录 last-good 快照（内存副本 + Redis 指针）
     *
     * @param scope    快照作用域
     * @param snapshot 刚成功写入的快照（空池快照不记录）
     */
    private void saveLastGood(String scope, FeedSnapshot snapshot) {
        // 空池快照没有可曝光条目，不覆盖上一代有效兜底内容
        if (snapshot == null || snapshot.getItems() == null || snapshot.getItems().isEmpty()) {
            return;
        }
        // 内存副本：Redis 不可用时的兜底来源
        lastGoodCache.put(scope, new LastGoodSnapshot(snapshot, System.currentTimeMillis()));
        // Redis 指针：跨实例/重启后可回读（Redis 异常由封装吞掉，不影响主流程）
        redisUtil.set(FeedConstant.SNAPSHOT_LAST_PREFIX + scope, snapshot.getSnapshotId(),
                contentProperties.getFeed().getStream().getSnapshotTtlSeconds(), TimeUnit.SECONDS);
    }

    /**
     * 判断内存副本是否超过快照 TTL
     *
     * @param savedAtMillis 副本保存时刻（epoch millis）
     * @return true=已过期；TTL 非正数视为不限制
     */
    private boolean isLastGoodExpired(long savedAtMillis) {
        long ttlMillis = contentProperties.getFeed().getStream().getSnapshotTtlSeconds() * 1000L;
        return ttlMillis > 0 && System.currentTimeMillis() - savedAtMillis > ttlMillis;
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
     * 构建当日点击键（日期 + 流类型 + 身份）
     *
     * <p>与曝光键同构：带流类型避免文章/书籍同号 ID 互相误判；带日期保证跨日重新计点击。</p>
     *
     * @param streamType 流类型
     * @param identity   身份标识
     * @return Redis key
     */
    private String buildClickedKey(String streamType, String identity) {
        return FeedConstant.CLICKED_KEY_PREFIX + streamType + ":" + identity + ":" + LocalDate.now().format(DATE_FORMATTER);
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

    /**
     * last-good 内存副本条目（快照 + 保存时刻，用于判断是否超过快照 TTL）
     */
    private static class LastGoodSnapshot {

        /** 最近一次成功写入的快照 */
        private final FeedSnapshot snapshot;

        /** 保存时刻（epoch millis，判断过期用） */
        private final long savedAtMillis;

        /**
         * 构造内存副本条目
         *
         * @param snapshot      成功写入的快照
         * @param savedAtMillis 保存时刻
         */
        private LastGoodSnapshot(FeedSnapshot snapshot, long savedAtMillis) {
            this.snapshot = snapshot;
            this.savedAtMillis = savedAtMillis;
        }

        /**
         * 获取快照
         *
         * @return 快照对象
         */
        private FeedSnapshot getSnapshot() {
            return snapshot;
        }

        /**
         * 获取保存时刻
         *
         * @return 保存时刻（epoch millis）
         */
        private long getSavedAtMillis() {
            return savedAtMillis;
        }
    }
}
