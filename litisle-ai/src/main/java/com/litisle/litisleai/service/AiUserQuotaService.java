package com.litisle.litisleai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litisleai.config.AiQuotaProperties;
import com.litisle.litisleai.domain.entity.AiUsageDaily;
import com.litisle.litisleai.mapper.AiUsageDailyMapper;
import com.litisle.litisleai.metrics.AiMetrics;
import com.litisle.litisleapi.dto.ai.AiPurposeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户级配额校验服务（1.3 / D5·V8：按 用户×用途×日 校验次数/Token 上限）
 *
 * <p>【口径】普通用户低配、按等级放开、VIP 高额度非不限量；超额拒绝并提示「今日额度已用完」。
 * 数值待定档（Q39），未定档前（上限 ≤0）仅计量不拦截——本服务返回放行，不产生拒绝。</p>
 *
 * <p>【接线说明】本批次只提供校验能力；1.4 机审通道无发起用户不消费本服务，
 * 用户维度拦截随板块二生成端点接线（届时等级/VIP 放开系数一并接入）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUserQuotaService {

    /** 配置（各用途日上限，≤0=未定档不拦截） */
    private final AiQuotaProperties quotaProperties;

    /** 日汇总 Mapper（读用户×用途×当日合计） */
    private final AiUsageDailyMapper usageDailyMapper;

    /** 指标打点（配额拒绝计数） */
    private final AiMetrics metrics;

    /** Redis 客户端（匿名问答日计数，P5：匿名无 userId 无法落日汇总表，走 Redis 独立计数） */
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /** 匿名计数键前缀 */
    private static final String ANON_KEY_PREFIX = "ai:quota:anon:";

    /**
     * 校验该用户该用途当日是否仍有额度（次数与 Token 双维度，任一超限即拒绝）
     *
     * @param userId  发起用户ID
     * @param purpose 用途打标
     * @return true=放行；false=超额拒绝（含 OTHER 兜底用途按放行处理）
     */
    public boolean tryAcquire(Long userId, AiPurposeEnum purpose) {
        // OTHER 仅兜底落明细，不做配额拦截
        if (!purpose.isMetered()) {
            return true;
        }
        AiQuotaProperties.Limit limit = resolveLimit(purpose);
        // 未定档（上限 ≤0）：仅计量不拦截
        if (limit.getDailyCalls() <= 0 && limit.getDailyTokens() <= 0) {
            return true;
        }
        // 当日该用户该用途合计（汇总表只有有用量才存在行，查无行=零用量放行）
        List<AiUsageDaily> rows = usageDailyMapper.selectList(new LambdaQueryWrapper<AiUsageDaily>()
                .eq(AiUsageDaily::getStatDate, LocalDate.now())
                .eq(AiUsageDaily::getUserId, userId)
                .eq(AiUsageDaily::getPurpose, purpose.getCode()));
        if (rows.isEmpty()) {
            return true;
        }
        AiUsageDaily today = rows.get(0);
        // 次数维度超限
        if (limit.getDailyCalls() > 0 && today.getCalls() != null && today.getCalls() >= limit.getDailyCalls()) {
            log.info("用户级配额拒绝（次数维度）userId={} purpose={} calls={} limit={}",
                    userId, purpose.getCode(), today.getCalls(), limit.getDailyCalls());
            metrics.recordQuotaExceeded(purpose.getCode());
            return false;
        }
        // Token 维度超限（输入+输出合计）
        if (limit.getDailyTokens() > 0 && today.getTokensIn() != null && today.getTokensOut() != null
                && today.getTokensIn() + today.getTokensOut() >= limit.getDailyTokens()) {
            log.info("用户级配额拒绝（Token 维度）userId={} purpose={} tokens={} limit={}",
                    userId, purpose.getCode(), today.getTokensIn() + today.getTokensOut(), limit.getDailyTokens());
            metrics.recordQuotaExceeded(purpose.getCode());
            return false;
        }
        return true;
    }

    /**
     * 匿名问答配额（P5 定档：按 IP 哈希×日 次数上限，Redis 日计数——匿名无 userId 无法
     * 归属日汇总表，独立 Redis 计数键 ai:quota:anon:{ip哈希}，当日过期）
     *
     * @param anonIpHash 调用方传入的 IP 哈希（网关脱敏，AI 不存原文）
     * @param dailyLimit 匿名日上限（rag.anon-qa-daily-limit）
     * @return true=放行；false=超额拒绝
     */
    public boolean tryAcquireAnonymous(String anonIpHash, int dailyLimit) {
        // 上限 ≤0：未定档仅计量不拦截（与用户级口径一致）
        if (dailyLimit <= 0 || anonIpHash == null || anonIpHash.isBlank()) {
            return true;
        }
        try {
            // Redis 日计数：INCR 自增，首次访问设置当日过期
            String key = ANON_KEY_PREFIX + anonIpHash + ":" + java.time.LocalDate.now();
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, java.time.Duration.ofHours(24));
            }
            if (count != null && count > dailyLimit) {
                log.info("匿名问答配额拒绝 anonIpHashChars={} count={} limit={}", anonIpHash.length(), count, dailyLimit);
                metrics.recordQuotaExceeded(com.litisle.litisleapi.dto.ai.AiPurposeEnum.QA.getCode());
                return false;
            }
            return true;
        } catch (Exception e) {
            // Redis 异常：放行降级（配额拦截能力降级，不阻断匿名问答主链路，告警可见）
            log.warn("匿名问答配额校验异常，降级放行", e);
            return true;
        }
    }

    /**
     * 用途 → 配额上限映射（机审用途无发起用户，读取仅为占位完整性）
     */
    private AiQuotaProperties.Limit resolveLimit(AiPurposeEnum purpose) {
        return switch (purpose) {
            case MODERATION_TEXT, MODERATION_IMAGE -> quotaProperties.getModeration();
            case SUMMARY -> quotaProperties.getSummary();
            case TAGGING -> quotaProperties.getTagging();
            case QA -> quotaProperties.getQa();
            case POLISH -> quotaProperties.getPolish();
            case STRUCTURE -> quotaProperties.getStructure();
            case OTHER -> new AiQuotaProperties.Limit();
        };
    }
}
