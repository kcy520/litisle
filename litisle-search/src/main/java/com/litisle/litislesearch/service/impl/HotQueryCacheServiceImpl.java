package com.litisle.litislesearch.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.dto.search.SearchRequestDTO;
import com.litisle.litisleapi.vo.search.SearchResultVO;
import com.litisle.litislesearch.config.SearchProperties;
import com.litisle.litislesearch.constant.SearchQueryConstant;
import com.litisle.litislesearch.constant.SearchRedisConstant;
import com.litisle.litislesearch.metrics.SearchMetrics;
import com.litisle.litislesearch.service.HotQueryCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
