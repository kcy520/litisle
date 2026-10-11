package com.litisle.litislereview.moderation;

import com.litisle.commonredis.constant.CacheConstant;
import com.litisle.commonredis.ratelimit.RedisRateLimiter;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型直调全站 QPS 护栏（板块三 3-8.3，§2.6 成本护栏）。
 *
 * 【为什么需要】3-8.3 故障降级场景下「Redis 缓存全失效 = 机审调用量放大 N 倍」
 * （缓存查重降级为跳过直调模型，只多花钱不漏审），护栏是直调模型不漏审前提下
 * 控制成本的最后一道闸；文本分片逐片调用 / 图片逐图调用，每次真实模型调用前申请一次配额。
 *
 * 【与 HTTP 全站拦截器的关系】ModerationGlobalQpsInterceptor 拦 HTTP 接口流量
 * （key=global:moderation:qps）；本护栏拦 MQ 消费链路的模型直调流量
 * （key=global:moderation:model-qps），两者隔离计数互不挤占，共用同一阈值口径
 * （litisle.moderation.qps.*，外置 yml/Nacos 运营可调）。
 *
 * 【Redis 降级兑底（宁严勿松）】复用 RedisRateLimiter 固定窗口计数：Redis 故障期
 * 内部自动切 LocalRateLimiter 本地兑底计数（分摊阈值，更严），本类无需自行处理降级。
 *
 * 【超限语义】抛 IllegalStateException（非 BizException）：MQ 消费链路按非预期异常
 * 走指数退避延迟重试（3-8.1），超限消息保持待审绝不 ack 丢弃，绝不 default-allow。
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationModelQpsGuard {

    /** 模型直调 QPS 限流 key（与 HTTP 全站拦截器 key 隔离，双 key 双口径互不挤占） */
    private static final String MODEL_QPS_KEY = CacheConstant.RATE_LIMIT + "global:moderation:model-qps";

    /** Redis 固定窗口限流器（Redis 故障期内部自动降级本地计数） */
    private final RedisRateLimiter redisRateLimiter;

    /** 机审可调参数（qps.windowSeconds 窗口 / qps.maxCount 全站阈值，与 HTTP 全站护栏同一阈值口径） */
    private final ModerationProperties moderationProperties;

    /**
     * 模型直调前申请一次全站 QPS 配额（每次真实 SPI 模型调用前调用一次）。
     *
     * @throws IllegalStateException 超限（消费链路按非预期异常走延迟重试，绝不 default-allow）
     */
    public void tryAcquire() {
        // 固定窗口计数：Redis 故障期 RedisRateLimiter 内部切本地兑底计数（宁严勿松）
        ModerationProperties.Qps qps = moderationProperties.getQps();
        long currentCount = redisRateLimiter.increment(MODEL_QPS_KEY, qps.getWindowSeconds());
        // 超限拦截：抛非 BizException，MQ 消费链路按非预期异常走延迟重试（保持待审，绝不放行）
        if (currentCount > qps.getMaxCount()) {
            log.warn("模型直调全站 QPS 超限，保持待审延迟重试，currentCount={}，maxCount={}",
                    currentCount, qps.getMaxCount());
            throw new IllegalStateException("模型直调全站 QPS 超限，稍后重试");
        }
    }
}