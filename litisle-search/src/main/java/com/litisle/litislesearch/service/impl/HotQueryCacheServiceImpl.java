package com.litisle.litislesearch.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchQueryConstant;
import com.litisle.litislesearch.constant.SearchRedisConstant;
import com.litisle.litislesearch.metrics.SearchMetrics;
import com.litisle.litislesearch.mq.IndexSyncConsumer;
import com.litisle.litislesearch.service.HotQueryCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.cache.CacheProperties.Redis;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 热点查询缓存服务实现（批次四 4.1 + 批次三 3.2 遗留「缓存失效」接入）。
 *
 * 【缓存结构】String 类型，值为 SearchResultVO 的 JSON（保留分页/来源/命中列表完整信息）；
 * key = search:hotquery:{type}:{sort}:{pageNum}:{pageSize}:{关键词+筛选条件 MD5}，TTL 取 cache.ttl（默认 10m）。
 * 【写入约束】只有 source=ES 的常规命中才写缓存；fallback / DEGRADED 结果一律不写。
 * 【容错】读/写异常只告警并放行（fail-open），缓存故障不得阻塞检索主链路；
 * 失效（evictAll）异常抛 IllegalStateException，交给 MQ 重试保证最终一致。
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HotQueryCacheServiceImpl implements HotQueryCacheService {

// 这个类里管理的是 两类完全不同用途的 Redis 键 ，它们的 SET 和 DELETE 各自成一条链路。分开讲：

// ## 第一类：业务缓存键 search:hotquery:{type}:{sort}:{pageNum}:{pageSize}:{md5}

// ## 第二类：脏标记键 search:hotquery-dirty
// 这是 失效的“合并开关” ，解决失效风暴问题。这是批次九优化一的核心设计。

// ### SET 的链路（事件端，极轻）
// 索引每收到一条变更事件（发布/元数据更新/书籍/用户）， IndexSyncConsumer.java 在完成索引同步后只调`markDirty()`
// 这里最精妙的是“先删标记、后清理”的顺序 （注释里写得很清楚）：


// ### DELETE 的链路（合并任务，每秒一次）
// DEL search:hotquery-dirty     ← 返回 true 才说明标记存在且"我抢到了清理权"
//    ├─ false：无标记 → 直接返回（空闲期每秒只有 1 次 O(1) DEL）
//    └─ true：抢到 → evictAll() 执行一次 SCAN + 分批 DEL
//          └─ 失败 → 重新 SET 脏标记再抛出（标记已删但没清成功，不能让变更丢失）
// - 先删标记：清理期间新事件打的标记会被 保留 （新 SET 重建了键），下一轮任务再清一次—— 多清无害，漏清致命
// - 若反过来先清后删：清理期间的新变更会被最后一步 DELETE 抹掉， 永久漏失效
// 失败时的补救也对称：清理抛异常前尽力重新打标；连打标都失败就留 error 日志，靠“下一个事件的 markDirty”或缓存 TTL 兜底。



// ## 标记的本质
// 这个标记就是一个 全局的“1 bit 开关” ，粒度粗到极简：
// `search:hotquery-dirty` 只是一个普通的 String 键，值永远是`"1"` ，没有任何内容信息。它不记录“谁变了”、“哪篇文章变了”、“哪个查询结果过期了”——它只回答一个是非题：
// 上次清理之后，索引发生过至少一次变更吗？
// ## 为什么“1 个标记就删所有”是可接受的？
// 粗粒度意味着 大量误伤 ：1 条书籍变更会把 10 分钟内刚缓存的所有查询结果（文章、用户……全部类型）一起清掉。这看似浪费，但仔细算账就会发现它是最优权衡：
// 正确性方向不对称。 缓存失效策略只有两种犯错方式：
// - 多删（误伤新鲜缓存） ：后果只是下次检索多查一次 ES，缓存重新回填。代价 = 一次 ES 查询。
// - 少删（漏掉过期缓存） ：用户会看到已经变更/下架的内容。代价 = 数据不一致，甚至隐私泄露（下架场景）。
// 为什么不建精确映射？ 精确失效需要一个“文章 1001 变了 → 哪些缓存键受影响”的反查结构。而热点查询缓存的键里只有查询条件的 MD5，你根本无法从“文章 1001 更新”反推出“哪些搜索关键词的缓存里包含这篇文章”——搜索结果是多对多的倒排关系，建映射的成本远超收益。全删是唯一既简单又保证正确的选择。
// 【SET 端】事件端，重复幂等
//   发布/更新/书籍/用户事件 ──→ SET search:hotquery-dirty = "1"，TTL 300s
//                               （N 条事件 = N 次重复 SET 同一个键，O(1)，无累积）
//                               （每次 SET 顺带刷新 TTL：持续变更期间标记不会中途过期）

// 【DEL 端】合并任务，竞争式获取
//   每秒一次：DEL search:hotquery-dirty
//     ├─ 返回 false（键不存在）→ 没有变更，本轮结束
//     └─ 返回 true → 抢到清理权 → SCAN + 全删缓存键
//           └─ 全删失败 → 重新 SET 标记（把"未完成的变更"补记回来）→ 抛异常

// 【TTL 兜底】300s
//   合并任务长期宕机 → 标记自清，不留残留
//   之后新事件到达会重新 SET → 恢复正常清理闭环

// 两个细节值得注意：
// 1. “先 DEL 标记后清理”是一种抢占语义。 DEL 返回 true 的那一刻，这个任务就独占了本轮清理权——即使多个实例都在跑，同一秒内也只有一个能抢到标记（Redis DEL 是原子的，不会两个都返回 true）。清理期间新事件打的标记是 新的一次 SET ，会保留到下一轮。
// 2. 重新打标（失败补偿）逻辑上等价于把“已消费但未完成的标记”恢复。 标记删了但清理失败，如果没有补偿，这一轮的变更就“被消费了却没生效”——重新 SET 就是把它记回来。极端情况下连补偿 SET 也失败（Redis 持续故障），还有最后一层兜底：下一个事件总会到达并重新打标，或缓存键自己走 TTL 过期。

// 1 秒一次的 EvictJob 删的是右边这种 ——查询结果缓存。它的触发源是 发布/元数据/书籍/用户 的索引变更（发布新文章、改标题、下架书籍等），不是互动事件。
// 1 秒删的是“所有人的”缓存——因为这个缓存根本没有“用户”维度。
// - 搜索主链路查的是 ES，不是 MySQL 。全文搜索、热度排序、时间衰减、语义向量检索（`embedding` 包）这些能力 MySQL 根本做不了（或做不好），这正是引入 ES 的原因。

// 用户 A（搜 "Java"，第 1 页）
//   ├─ 请求 1（T+0s）：缓存 miss → 查 1 次 ES → 结果写入缓存
//   ├─ 请求 2（T+2min）：命中缓存，0 次 ES
//   ├─ 期间有人发布了新文章 → 1s 内缓存全删（对所有人生效）
//   ├─ 请求 3（T+5min）：miss（被删了）→ 再查 1 次 ES → 重新回填
//   └─ 他正在看的页面：自始至终没有变化，直到他主动发起新请求


    /** Redis 字符串模板（缓存读写 + SCAN 失效） */
    private final StringRedisTemplate stringRedisTemplate;

    /** search 配置（缓存开关与 TTL 外置可调） */
    private final SearchProperties properties;

    /** 全局 ObjectMapper（SearchResultVO 的 JSON 序列化/反序列化，含 JavaTime 模块） */
    private final ObjectMapper objectMapper;

    /** 观测指标（脏标记写入/合并失效结果计数，Micrometer 未装配时静默空转） */
    private final SearchMetrics searchMetrics;

    /**
     * 读取缓存：命中直接返回；未命中/关闭/异常返回 null。
     *
     * @param cacheKey 缓存键
     * @return 缓存结果或 null
     */
    @Override
    public SearchResultVO read(String cacheKey) {

        // search:hotquery:article:heat:1:20:3f5a8c...d2e1
        //          │       │  │ │   └─ MD5 摘要（32位）
        //          │       │  │ └─ pageSize
        //          │       │  └─ pageNum
        //          │       └─ 排序方式
        //          └─ 检索类型
        // 也就是说， 用户真正输入的东西（关键词、筛选条件）全被打包进了一个 MD5 摘要里 。比如：

        // 缓存关闭：不读取（避免旧键在开关关闭期间仍生效）
        if (!properties.getCache().isHotQueryEnabled()) {
            return null;
        }
        try {
            String json = stringRedisTemplate.opsForValue().get(cacheKey);
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, SearchResultVO.class);
        } catch (Exception e) {
            // 反序列化失败/Redis 异常：按未命中处理，放行查 ES（fail-open）
            log.warn("[热点查询缓存] 读取失败，降级查 ES：key={}", cacheKey, e);
            return null;
        }
    }

    /**
     * 写入缓存：仅缓存常规 ES 命中结果，兜底/降级结果不写。
     *
     * @param cacheKey 缓存键
     * @param result   检索结果
     */
    @Override
    public void write(String cacheKey, SearchResultVO result) {
        // 缓存关闭或结果为空：不写
        if (!properties.getCache().isHotQueryEnabled() || result == null) {
            return;
        }
        // 兜底/降级结果不是真实命中，禁止写入缓存（批次A A1/A3 定案）
        if (!SearchQueryConstant.SEARCH_SOURCE_ES.equals(result.getSource())) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            stringRedisTemplate.opsForValue().set(cacheKey, json, properties.getCache().getTtl());
        } catch (Exception e) {
            // 写缓存失败不影响本次检索结果返回（下次请求重新查 ES）
            log.warn("[热点查询缓存] 写入失败，忽略本次缓存：key={}", cacheKey, e);
        }
    }

    /**
     * 构造缓存键：类型/排序/分页明文 + 关键词与筛选条件的 MD5 指纹。
     *
     * 明文拼“分页 + 排序 + 类型”，对“关键词 + 筛选条件”做 MD5，最终拼成一个 Redis 缓存键。
     *
     * @param request  原始检索请求
     * @param typeCode 检索类型 code
     * @param sortCode 排序 code
     * @param pageNum  页码
     * @param pageSize 每页条数
     * @param keyword  已归一化的查询词
     * @return Redis 缓存键
     */
    @Override
    public String buildKey(SearchRequestDTO request, String typeCode, String sortCode,
                           int pageNum, int pageSize, String keyword) {
        // 查询指纹：关键词与全部筛选条件按固定顺序拼接，再摘要（相同查询 → 相同键）
        String identity = String.join(SearchRedisConstant.CACHE_IDENTITY_SEPARATOR,
                keyword,
                nullToEmpty(request.getTag()),
                nullToEmpty(request.getCategory()),
                nullToEmpty(request.getZone()),
                nullToEmpty(request.getPublishBegin()),
                nullToEmpty(request.getPublishEnd()));
        // MD5 仅用于生成稳定短键（非安全用途），避免用户输入原样进入 Redis 键
            //本质是：把“任意长度的查询条件”映射成“固定长度的字符串”
            //同时排除用户输入的特殊字符等不安全内容
        String digest = DigestUtils.md5DigestAsHex(identity.getBytes(StandardCharsets.UTF_8));
        return SearchRedisConstant.HOT_QUERY_KEY_PREFIX
                + typeCode + SearchRedisConstant.CACHE_KEY_SEPARATOR
                + sortCode + SearchRedisConstant.CACHE_KEY_SEPARATOR
                + pageNum + SearchRedisConstant.CACHE_KEY_SEPARATOR
                + pageSize + SearchRedisConstant.CACHE_KEY_SEPARATOR
                + digest;
    }

    /**
     * 全量失效热点查询缓存：SCAN 收集全部键后分批删除。
     *
     * 【异常】Redis 故障抛 IllegalStateException（可恢复），由调用方（MQ 消费）触发延迟重试，
     * 确保下架等场景的缓存最终被清理（不能静默留下过期前仍可见的隐私内容）
     */
    @Override
    public void evictAll() {
        // 缓存关闭时没有业务缓存可清（旧键的兜底过期交给 TTL）
        if (!properties.getCache().isHotQueryEnabled()) {
            return;
        }
        // 匹配模式：固定前缀 + 通配符（前缀为服务端常量，无用户可控原串）
        String pattern = SearchRedisConstant.HOT_QUERY_KEY_PREFIX + "*";
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions()
                        .match(pattern)
                        .count(SearchRedisConstant.HOT_QUERY_EVICT_SCAN_COUNT)
                        .build())) {
            cursor.forEachRemaining(keys::add);
        } catch (Exception e) {
            throw new IllegalStateException("扫描热点查询缓存键失败（可重试）", e);
        }
        if (keys.isEmpty()) {
            return;
        }
        try {
            // 分批删除：单次 DEL 参数过多会增大请求体积，按 SCAN 建议条数分批
            for (int from = 0; from < keys.size(); from += SearchRedisConstant.HOT_QUERY_EVICT_SCAN_COUNT) {
                int to = Math.min(from + SearchRedisConstant.HOT_QUERY_EVICT_SCAN_COUNT, keys.size());
                stringRedisTemplate.delete(keys.subList(from, to));
            }
            log.info("[热点查询缓存] 失效完成：删除键数={}", keys.size());
        } catch (Exception e) {
            throw new IllegalStateException("删除热点查询缓存键失败（可重试）", e);
        }
    }

    /**
     * 标记缓存待失效：只写带短 TTL 的脏标记，不做 SCAN。
     *
     * <p>Redis 故障抛 IllegalStateException（可恢复异常），交给 MQ 延迟重投；
     * 不能静默吞掉，否则索引已变更但缓存永远不会被合并清理。</p>
     */
    @Override
    public void markDirty() {
        // 缓存关闭时无需标记（没有业务缓存可清）
        if (!properties.getCache().isHotQueryEnabled()) {
            return;
        }
        try {
            // 脏标记 TTL 由常量统一收口：短 TTL 防合并任务长期不可用时标记永久残留
            stringRedisTemplate.opsForValue().set(SearchRedisConstant.HOT_QUERY_DIRTY_KEY,
                    "1", Duration.ofSeconds(SearchRedisConstant.HOT_QUERY_DIRTY_TTL_SECONDS));
            searchMetrics.recordCacheDirtyMark(true);
        } catch (Exception e) {
            // 指标 + error 日志留痕后抛出，由 MqAckHelper 走延迟重投（不静默吞）
            searchMetrics.recordCacheDirtyMark(false);
            log.error("[热点查询缓存] 脏标记写入失败（可重试）：key={}", SearchRedisConstant.HOT_QUERY_DIRTY_KEY, e);
            throw new IllegalStateException("写入热点查询缓存脏标记失败（可重试）", e);
        }
    }

    /**
     * 合并失效：存在脏标记时先删标记再执行一次 evictAll。
     *
     * <p>先删标记后清理：清理期间新事件写入的标记会被保留，下一轮再清一次（多清不会漏清）；
     * 若反过来先清后删，清理期间的新变更会被同一次删除抹掉，造成永久漏失效。</p>
     */
    @Override
    public boolean evictIfDirty() {
        // 缓存关闭时没有业务缓存可清（旧键交给 TTL 兜底）
        if (!properties.getCache().isHotQueryEnabled()) {
            return false;
        }
        try {
            // delete 返回 true 表示标记存在且本次删除成功（即本轮需要清理）
            Boolean removed = stringRedisTemplate.delete(SearchRedisConstant.HOT_QUERY_DIRTY_KEY);
            if (!Boolean.TRUE.equals(removed)) {
                return false;
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取热点查询缓存脏标记失败（可重试）", e);
        }
        try {
            evictAll();
            return true;
        } catch (RuntimeException e) {
            // 清理失败：尽力重新打标，保证下一轮继续清理（标记已删但未清成功不能就此丢失）
            try {
                stringRedisTemplate.opsForValue().set(SearchRedisConstant.HOT_QUERY_DIRTY_KEY,
                        "1", Duration.ofSeconds(SearchRedisConstant.HOT_QUERY_DIRTY_TTL_SECONDS));
            } catch (Exception remarkError) {
                log.error("[热点查询缓存] 清理失败后重新打标也失败，失效可能延迟到 TTL 后由新事件触发",
                        remarkError);
            }
            throw e;
        }
    }

    /**
     * 空值转空串（参与指纹拼接，保证字段边界稳定）。
     *
     * @param value 原值
     * @return 非空值或空串
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
