package com.litisle.litisleai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litisleai.config.AiBudgetProperties;
import com.litisle.litisleai.domain.entity.AiUsageDaily;
import com.litisle.litisleai.enums.AiBudgetDecision;
import com.litisle.litisleai.mapper.AiUsageDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 预算分账判定服务（1.3 / D15：机审桶 = 文本+图片合并，SUMMARY/TAGGING/QA 各自独立桶）
 *
 * <p>【判定口径】读 ai_usage_daily 当日（跨用户求和）合计成本，本地缓存 60 秒，
 * 不在每次调用读写预算键；预算值来自 AiBudgetProperties，≤0 表示未设预算只计量不限额；
 * 返回 ALLOWED（<80%）/ WARN（≥80%）/ EXCEEDED（100%）。</p>
 *
 * <p>【执行语义】EXCEEDED 只停该用途新请求——生成类停用返回明确错误码；机审桶不停调用
 * （转全量人工 + 告警），执行语义由调用方（AiChatService）区分，本服务只做判定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBudgetService {

    /** 预算桶-机审（MODERATION_TEXT + MODERATION_IMAGE 合并计费） */
    private static final String BUCKET_MODERATION = "moderation";

    /** 预算判定缓存有效期（毫秒）：判定读汇总表 + 本地缓存 60 秒 */
    private static final long CACHE_TTL_MS = 60_000L;

    /** 预算配置（桶预算值与预警线） */
    private final AiBudgetProperties budgetProperties;

    /** 日汇总 Mapper（当日合计读取） */
    private final AiUsageDailyMapper usageDailyMapper;

    /** 预算桶 → 桶内用途集合的映射（MODERATION_TEXT/IMAGE 同属机审桶；POLISH/STRUCTURE 板块三 3.3 各立独立桶） */
    private final Map<String, Set<String>> bucketPurposes = Map.of(
            BUCKET_MODERATION, Set.of("MODERATION_TEXT", "MODERATION_IMAGE"),
            "summary", Set.of("SUMMARY"),
            "tagging", Set.of("TAGGING"),
            "qa", Set.of("QA"),
            "polish", Set.of("POLISH"),
            "structure", Set.of("STRUCTURE"));

    /** 预算桶当日成本缓存（key=桶名；60 秒过期，避免每次调用读库） */
    private final Map<String, CachedCost> costCache = new ConcurrentHashMap<>();

    /**
     * 判定该用途所在预算桶的当日状态
     *
     * @param purpose 用途打标（OTHER 不设预算，直接放行）
     * @return ALLOWED / WARN（≥80%）/ EXCEEDED（100%）
     */
    public AiBudgetDecision check(com.litisle.litisleapi.dto.ai.AiPurposeEnum purpose) {
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
     * 用途 → 预算桶映射（机审文本/图片同桶，其余各归独立桶）
     */
    private String resolveBucket(com.litisle.litisleapi.dto.ai.AiPurposeEnum purpose) {
        return switch (purpose) {
            case MODERATION_TEXT, MODERATION_IMAGE -> BUCKET_MODERATION;
            case SUMMARY -> "summary";
            case TAGGING -> "tagging";
            case QA -> "qa";
            case POLISH -> "polish";
            case STRUCTURE -> "structure";
            case OTHER -> "other";
        };
    }

    /**
     * 桶预算值读取（≤0=未设预算）
     */
    private BigDecimal resolveBudget(String bucket) {
        return switch (bucket) {
            case BUCKET_MODERATION -> budgetProperties.getModeration();
            case "summary" -> budgetProperties.getSummary();
            case "tagging" -> budgetProperties.getTagging();
            case "qa" -> budgetProperties.getQa();
            case "polish" -> budgetProperties.getPolish();
            case "structure" -> budgetProperties.getStructure();
            default -> BigDecimal.ZERO;
        };
    }

    /**
     * 桶当日成本合计（先读 60 秒本地缓存，过期再查 ai_usage_daily 当日跨用户求和）
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
     */
    private record CachedCost(BigDecimal cost, long cachedAt) {
    }
}
