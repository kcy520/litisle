package com.litisle.litisleai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litisleai.config.AiBudgetProperties;
import com.litisle.litisleai.domain.entity.AiUsageDaily;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.mapper.AiUsageDailyMapper;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 预算分账判定服务（1.3 / D15：机审桶 = 文本+图片合并，SUMMARY/TAGGING/QA 各自独立桶）
 *
 * <p>【职责边界】本类做三件事——①判定：调用前按 Redis 日计数原子比对预算（Lua 脚本）；
 * ②记账：调用后由计量服务（AiUsageRecordService）回调 recordCost 实扣实际成本；
 * ③对账：聚合任务（AiUsageDailyAggregateTask）回调 reconcileBuckets，以账本值向上修正
 * Redis 计数缺口。判定/执行语义边界不变：本服务只"判定"不"执行"，EXCEEDED 之后是拒
 * 还是继续，由调用方（AiChatService 等）按用途自己决定。</p>
 *
 * <p>【判定口径】优先读 Redis 日计数键 ai:budget:cost:{bucket}:{yyyyMMdd}（Lua 原子
 * GET+比较）；键缺失（新一天/Redis 重启）读账本 ai_usage_daily 当日合计预热后判定；
 * Redis 不可用整体降级账本口径。预算值来自 AiBudgetProperties，≤0 表示未设预算只计量
 * 不限额；返回 ALLOWED（&lt;80%）/ WARN（≥80%）/ EXCEEDED（100%）。</p>
 *
 * <p>【账本兜底】MySQL（ai_usage_daily）是成本权威源：Redis 只承载当日实时增量，
 * 计数键 48 小时过期自动废弃；Redis 故障期间漏记的增量由对账任务按账本值只增不减地
 * 补齐，Redis 与账本间允许秒级小额漂移（预算闸门防失控，不作计费出账口径）。</p>
 *
 * <p>【执行语义】EXCEEDED 只停该用途新请求——生成类停用返回明确错误码；机审桶不停调用
 * （转全量人工 + 告警），执行语义由调用方（AiChatService）区分，本服务只做判定。</p>
 *
 * <p>【通俗说明】AI 的"钱包管家"：平台给每类 AI 用途设了当日花钱上限（预算桶），
 * 本类负责在每次调用前算账——今天这个桶花了多少，没到 80% 放行、到 80% 提醒、
 * 到 100% 亮红灯。账本实时记在 Redis 里（每次调用后自动累加，原子防并发），MySQL
 * 汇总表是总账本：Redis 里查不到账（新的一天/重启）或 Redis 挂了，就翻账本算；
 * 定时任务还会拿账本核对 Redis，漏记的钱补回去。机审的文本审核和图片审核共用一个桶，
 * 其他用途各自独立记账。注意它只"判定"不"执行"：红灯亮了之后是拒还是继续，
 * 由调用方按用途自己决定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBudgetService {

    /** 预算桶-机审（MODERATION_TEXT + MODERATION_IMAGE 合并计费） */
    private static final String BUCKET_MODERATION = "moderation";

    /** 预算桶-摘要（与 AiBudgetProperties 配置键同名） */
    private static final String BUCKET_SUMMARY = "summary";

    /** 预算桶-标签 */
    private static final String BUCKET_TAGGING = "tagging";

    /** 预算桶-问答 */
    private static final String BUCKET_QA = "qa";

    /** 预算桶-润色 */
    private static final String BUCKET_POLISH = "polish";

    /** 预算桶-结构分析 */
    private static final String BUCKET_STRUCTURE = "structure";

    /** 预算桶-相似推荐 */
    private static final String BUCKET_SIMILAR = "similar";

    /** 预算桶-索引期打标（LABELING，板块八 1.3：ai_labels 维度标注管线，独立于用户端点各桶） */
    private static final String BUCKET_LABELING = "labeling";

    /** 预算桶-未归类（OTHER 不计预算，仅保持映射完整性；check 入口已提前放行） */
    private static final String BUCKET_OTHER = "other";

    /** 预算判定缓存有效期（毫秒）：降级读账本 + 本地缓存 60 秒 */
    private static final long CACHE_TTL_MS = 60_000L;

    /** Redis 计数键前缀（完整键：ai:budget:cost:{bucket}:{yyyyMMdd}） */
    private static final String COST_KEY_PREFIX = "ai:budget:cost:";

    /** Redis 计数键 TTL（48 小时覆盖跨天临界，过期自动废弃，次日判定按键缺失触发账本预热） */
    private static final Duration COST_KEY_TTL = Duration.ofHours(48);

    /** 成本转整数分的放大倍数（cost 两位小数，×100 后整数累加无浮点漂移） */
    private static final BigDecimal FEN_MULTIPLIER = BigDecimal.valueOf(100);

    /** 判定脚本（调用前原子比对）：键缺失返回 3（触发账本预热）；≥限额 2、≥预警线 1、其余 0 */
    private static final DefaultRedisScript<Long> CHECK_SCRIPT = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return 3 end
            local cur = tonumber(v)
            if cur >= tonumber(ARGV[2]) then return 2 end
            if cur >= tonumber(ARGV[1]) then return 1 end
            return 0""", Long.class);

    /** 记账脚本（调用后实扣）：INCRBY 原子累加整数分并刷新 TTL */
    private static final DefaultRedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>("""
            local v = redis.call('INCRBY', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return v""", Long.class);

    /** 对账脚本（只增不减）：当前值 < 账本值才覆盖写入（带 TTL），防止抹掉其他节点刚 INCR 的在途增量 */
    private static final DefaultRedisScript<Long> RECONCILE_SCRIPT = new DefaultRedisScript<>("""
            local cur = redis.call('GET', KEYS[1])
            local dbFen = tonumber(ARGV[1])
            if cur and tonumber(cur) >= dbFen then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return 1""", Long.class);

    /** 预算配置（桶预算值与预警线） */
    private final AiBudgetProperties budgetProperties;

    /** 日汇总 Mapper（账本口径读取：降级判定 + 对账修正） */
    private final AiUsageDailyMapper usageDailyMapper;

    /** Redis 客户端（日计数读写，沿用 AiUserQuotaService 匿名计数的降级惯例） */
    private final StringRedisTemplate redisTemplate;

    /** 预算桶 → 桶内用途集合的映射（MODERATION_TEXT/IMAGE 同属机审桶；POLISH/STRUCTURE 板块三 3.3、SIMILAR 板块五 5.1、LABELING 板块八 1.3 各立独立桶） */
    private final Map<String, Set<AiPurposeEnum>> bucketPurposes = Map.of(
            BUCKET_MODERATION, Set.of(AiPurposeEnum.MODERATION_TEXT, AiPurposeEnum.MODERATION_IMAGE),
            BUCKET_SUMMARY, Set.of(AiPurposeEnum.SUMMARY),
            BUCKET_TAGGING, Set.of(AiPurposeEnum.TAGGING),
            BUCKET_QA, Set.of(AiPurposeEnum.QA),
            BUCKET_POLISH, Set.of(AiPurposeEnum.POLISH),
            BUCKET_STRUCTURE, Set.of(AiPurposeEnum.STRUCTURE),
            BUCKET_SIMILAR, Set.of(AiPurposeEnum.SIMILAR),
            BUCKET_LABELING, Set.of(AiPurposeEnum.LABELING));

    /** 账本口径桶成本缓存（key=桶名；降级路径 60 秒过期，避免 Redis 故障期压垮 DB） */
    private final Map<String, CachedCost> costCache = new ConcurrentHashMap<>();

    /**
     * 判定该用途所在预算桶的当日状态
     *
     * @param purpose 用途打标（OTHER 不设预算，直接放行）
     * @return ALLOWED / WARN（≥80%）/ EXCEEDED（100%）
     *
     * <p>【降级语义】Redis 键缺失→读账本预热后按账本口径返回；Redis 不可用→直接降级
     * 账本口径（60 秒缓存），闸门能力降级但不失效，不抛异常。</p>
     *
     * <p>【通俗说明】判定四连：未归类用途（OTHER）直接放行 → 找到所属桶 → 桶没设
     * 预算（≤0）也放行 → 看账：Redis 里有实时账就原子比对（100% 红灯、≥80% 黄灯、
     * 其余绿灯）；Redis 里没账或 Redis 挂了就翻 MySQL 账本算。</p>
     */
    public AiBudgetDecision check(AiPurposeEnum purpose) {
        /*
         * 业务逻辑步骤：
         * 1. OTHER 用途与未设预算桶（≤0）直接放行（口径不变）；
         * 2. 执行 Lua 判定脚本（GET+比较原子执行），返回值映射三态；
         * 3. 键缺失（返回 3）：读账本预热 Redis 计数键后按账本口径返回；
         * 4. Redis 异常：降级账本口径判定，告警可见。
         */
        // OTHER 仅兜底落明细，不计入预算（不拦截、不告警——告警由端点侧「未归类调用」口径承担）
        if (!purpose.isMetered()) {
            return AiBudgetDecision.ALLOWED;
        }
        String bucket = resolveBucket(purpose);
        BigDecimal budget = resolveBudget(bucket);
        // ≤0 = 未设预算：只计量不限额
        if (budget.compareTo(BigDecimal.ZERO) <= 0) {
            return AiBudgetDecision.ALLOWED;
        }
        try {
            // 阈值在 Java 侧换算整数分传参，避免 Lua 浮点（warn=预算×预警线，limit=预算）
            Long result = redisTemplate.execute(CHECK_SCRIPT, List.of(costKey(bucket, LocalDate.now())),
                    String.valueOf(toFen(budget.multiply(BigDecimal.valueOf(budgetProperties.getWarnRatio())))),
                    String.valueOf(toFen(budget)));
            if (result != null && result == 3L) {
                // 键缺失（新一天/重启/过期）：账本值预热（NX 写入防覆盖在途增量），按账本口径返回
                warmBucketKey(bucket, todayBucketCost(bucket));
                return decideByLedger(bucket, budget);
            }
            // 脚本无返回值视为异常输出：按 Redis 故障降级处理
            if (result == null) {
                return decideByLedger(bucket, budget);
            }
            return mapDecision(result.intValue());
        } catch (Exception e) {
            // Redis 不可用：降级账本口径（60 秒缓存），闸门不因 Redis 故障失效
            log.warn("Redis 预算判定异常，降级账本口径 bucket={}", bucket, e);
            return decideByLedger(bucket, budget);
        }
    }

    /**
     * 记录一次调用的实际成本到所在预算桶的 Redis 日计数（调用后实扣，INCRBY 原子累加）
     *
     * @param purpose  用途打标（未归类 OTHER 不记账）
     * @param cost     本次调用成本（元，两位小数；null/≤0 跳过）
     * @param statDate 成本归属日（明细以 occurred_at 为权威源，跨天落库必须记到归属日键）
     *
     * <p>【降级语义】Redis 异常仅告警不影响计量落库——计数缺口由对账任务按账本值
     * 向上修正，不重试不抛异常。</p>
     *
     * <p>【通俗说明】调用成功落完明细后，顺手把这个桶今天的账 +上这笔钱（原子累加，
     * 多实例并发也不会算错）；Redis 挂了就只记 warn 日志，明细账本里有数，对账任务
     * 会补回来。</p>
     */
    public void recordCost(AiPurposeEnum purpose, BigDecimal cost, LocalDate statDate) {
        /*
         * 业务逻辑步骤：
         * 1. 未归类用途与零成本（失败明细/无单价配置）直接跳过；
         * 2. 成本换算整数分 → Lua INCRBY 原子累加并刷新 TTL；
         * 3. Redis 异常仅告警：账本仍有明细，对账任务兜底修正。
         */
        // 未归类用途不入桶；零成本无需累加
        if (!purpose.isMetered() || cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        // 元 → 整数分（两位小数 ×100），异常精度 HALF_UP 兜底
        long fen = toFen(cost);
        if (fen <= 0) {
            return;
        }
        try {
            redisTemplate.execute(RECORD_SCRIPT, List.of(costKey(resolveBucket(purpose), statDate)),
                    String.valueOf(fen), String.valueOf(COST_KEY_TTL.getSeconds()));
        } catch (Exception e) {
            // 记账失败不影响计量主链路：计数缺口由对账任务只增不减地补齐
            log.warn("Redis 预算计数累加失败 purpose={} statDate={}", purpose.getCode(), statDate, e);
        }
    }

    /**
     * 对账修正（只增不减）：以账本（ai_usage_daily）当日合计为准，Redis 计数偏小时向上覆盖
     *
     * @param statDate 对账归属日（仅当日键参与判定，历史日期键已过期废弃无需回补）
     *
     * <p>【修正语义】只增不减：Redis 当前值 ≥ 账本值时不写——防止把其他节点刚 INCR
     * 的在途增量抹掉；Redis 故障期间漏记的增量由本方法按账本值补齐。单桶失败仅告警，
     * 不阻断其余桶。</p>
     *
     * <p>【通俗说明】定时拿 MySQL 总账核对 Redis 的实时账：Redis 记少了（故障漏记）
     * 就按总账补上去；Redis 记得只多不少就别动——多记可能是别的节点刚加的，抹掉
     * 会造成重复漏记。</p>
     */
    public void reconcileBuckets(LocalDate statDate) {
        /*
         * 业务逻辑步骤：
         * 1. 读账本当日全部汇总行（跨用户）；
         * 2. 按预算桶归组求和，预算 >0 的桶逐个执行对账脚本（只增不减，带 TTL）；
         * 3. 单桶异常仅告警，不影响其余桶与聚合任务主流程。
         */
        List<AiUsageDaily> rows = usageDailyMapper.selectList(new LambdaQueryWrapper<AiUsageDaily>()
                .eq(AiUsageDaily::getStatDate, statDate));
        for (Map.Entry<String, Set<AiPurposeEnum>> entry : bucketPurposes.entrySet()) {
            BigDecimal budget = resolveBudget(entry.getKey());
            // 未设预算的桶无判定意义，跳过对账
            if (budget.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            // 归桶求和：账本口径为权威源（cost 空值按 0 兜底）
            BigDecimal sum = BigDecimal.ZERO;
            for (AiUsageDaily row : rows) {
                if (entry.getValue().contains(row.getPurpose())) {
                    sum = sum.add(row.getCost() == null ? BigDecimal.ZERO : row.getCost());
                }
            }
            try {
                redisTemplate.execute(RECONCILE_SCRIPT, List.of(costKey(entry.getKey(), statDate)),
                        String.valueOf(toFen(sum)), String.valueOf(COST_KEY_TTL.getSeconds()));
            } catch (Exception e) {
                // 单桶对账失败只影响该桶精度，不阻断其余桶
                log.warn("Redis 预算对账失败 bucket={} statDate={}", entry.getKey(), statDate, e);
            }
        }
    }

    /**
     * 用途 → 预算桶映射（机审文本/图片同桶，其余各归独立桶）
     *
     * <p>【通俗说明】查"这个用途记哪个桶的账"：机审文本和图片合并记机审桶，
     * 摘要、标签、问答等各记各的桶。</p>
     */
    private String resolveBucket(AiPurposeEnum purpose) {
        return switch (purpose) {
            case MODERATION_TEXT, MODERATION_IMAGE -> BUCKET_MODERATION;
            case SUMMARY -> BUCKET_SUMMARY;
            case TAGGING -> BUCKET_TAGGING;
            case QA -> BUCKET_QA;
            case POLISH -> BUCKET_POLISH;
            case STRUCTURE -> BUCKET_STRUCTURE;
            case SIMILAR -> BUCKET_SIMILAR;
            case LABELING -> BUCKET_LABELING;
            case OTHER -> BUCKET_OTHER;
        };
    }

    /**
     * 桶预算值读取（≤0=未设预算）
     *
     * <p>【通俗说明】从配置读某个桶的当日预算额度，没设或设了 0 表示不限额。</p>
     */
    private BigDecimal resolveBudget(String bucket) {
        return switch (bucket) {
            case BUCKET_MODERATION -> budgetProperties.getModeration();
            case BUCKET_SUMMARY -> budgetProperties.getSummary();
            case BUCKET_TAGGING -> budgetProperties.getTagging();
            case BUCKET_QA -> budgetProperties.getQa();
            case BUCKET_POLISH -> budgetProperties.getPolish();
            case BUCKET_STRUCTURE -> budgetProperties.getStructure();
            case BUCKET_SIMILAR -> budgetProperties.getSimilar();
            case BUCKET_LABELING -> budgetProperties.getLabeling();
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * Lua 判定结果 → 决策枚举映射（0=放行、1=预警、2=超限）
     */
    private AiBudgetDecision mapDecision(int code) {
        return switch (code) {
            case 2 -> AiBudgetDecision.EXCEEDED;
            case 1 -> AiBudgetDecision.WARN;
            default -> AiBudgetDecision.ALLOWED;
        };
    }

    /**
     * 账本口径判定（Redis 降级/预热路径）：读 ai_usage_daily 当日合计（60 秒缓存）与预算比对
     *
     * <p>【通俗说明】翻 MySQL 账本算"这个桶今天花了多少"（带 60 秒缓存防 Redis 故障
     * 期压垮 DB），然后按同一套阈值亮灯：100% 红灯、≥80% 黄灯、其余绿灯。</p>
     */
    private AiBudgetDecision decideByLedger(String bucket, BigDecimal budget) {
        BigDecimal used = todayBucketCost(bucket);
        // 100%：只停该用途新请求（执行语义由调用方区分机审/生成）
        if (used.compareTo(budget) >= 0) {
            return AiBudgetDecision.EXCEEDED;
        }
        // ≥80%：预警只提醒不停用
        if (used.compareTo(budget.multiply(BigDecimal.valueOf(budgetProperties.getWarnRatio()))) >= 0) {
            return AiBudgetDecision.WARN;
        }
        return AiBudgetDecision.ALLOWED;
    }

    /**
     * 预热 Redis 计数键（账本值 NX 写入：仅键缺失时写，防覆盖其他节点已累计的在途增量）
     *
     * <p>【降级语义】预热失败只影响下次判定继续走降级路径，不影响本次判定结果
     * （本次已按账本口径返回），不抛异常。</p>
     */
    private void warmBucketKey(String bucket, BigDecimal usedCost) {
        try {
            // setIfAbsent（NX 语义）：键已被其他节点预热/累计时放弃写入
            redisTemplate.opsForValue().setIfAbsent(costKey(bucket, LocalDate.now()),
                    String.valueOf(toFen(usedCost)), COST_KEY_TTL);
        } catch (Exception e) {
            log.warn("Redis 预算计数键预热失败 bucket={}", bucket, e);
        }
    }

    /**
     * Redis 计数完整键：ai:budget:cost:{bucket}:{yyyyMMdd}
     */
    private String costKey(String bucket, LocalDate statDate) {
        return COST_KEY_PREFIX + bucket + ":" + statDate;
    }

    /**
     * 元 → 整数分（两位小数 ×100；异常精度 HALF_UP 兜底，负值由调用方过滤）
     */
    private long toFen(BigDecimal cost) {
        return cost.multiply(FEN_MULTIPLIER).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * 桶当日成本合计（降级路径：先读 60 秒本地缓存，过期再查 ai_usage_daily 当日跨用户求和）
     *
     * <p>【通俗说明】算"这个桶今天花了多少钱"：先看 60 秒内算过没有，算过直接用
     * 缓存结果；过期了就查日汇总表把桶内所有用途的成本加起来（全局口径，不分用户），
     * 算完写回缓存。</p>
     */
    private BigDecimal todayBucketCost(String bucket) {
        CachedCost cached = costCache.get(bucket);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.cachedAt < CACHE_TTL_MS) {
            return cached.cost;
        }
        // 当日跨用户求和：预算是全局口径（按桶），用户粒度由配额服务承担
        List<AiUsageDaily> rows = usageDailyMapper.selectList(new LambdaQueryWrapper<AiUsageDaily>()
                .eq(AiUsageDaily::getStatDate, LocalDate.now())
                .in(AiUsageDaily::getPurpose, bucketPurposes.get(bucket)));
        BigDecimal sum = BigDecimal.ZERO;
        for (AiUsageDaily row : rows) {
            sum = sum.add(row.getCost() == null ? BigDecimal.ZERO : row.getCost());
        }
        costCache.put(bucket, new CachedCost(sum, now));
        return sum;
    }

    /**
     * 缓存条目（桶成本 + 缓存时间戳）
     *
     * <p>【通俗说明】一条缓存：桶今天花了多少钱 + 什么时候算的（超过 60 秒就作废重算）。</p>
     */
    private record CachedCost(BigDecimal cost, long cachedAt) {
    }
}
