package com.litisle.litisleauth.service.impl;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.litisle.litisleapi.dto.moderation.PunishStatusDTO;
import com.litisle.litisleauth.constant.AuthCacheKey;
import com.litisle.litisleauth.service.PunishSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 禁言/封禁态快照维护实现（B3，骨架落位、实现挂起）
 *
 *【触发条件】随 B1 消费端实现一并落地（review 侧 5-2.1 事件发布先行）——实现已就绪
 *
 *【落地要点（已按此实现）】
 * - Redis key：auth:punish:snapshot:{userId}（userId 数字派生禁拼原串；常量落 AuthCacheKey）
 * - value：PunishStatusDTO JSON（Jackson 序列化，禁 Java 原生反序列化）
 * - TTL：短 TTL 外置配置（litisle.moderation.punish.snapshot-ttl-seconds 默认 300）±10% 抖动
 *  （防同批用户快照集体过期雪崩，通用纪律⑥）
 * - 本地缓存【暂不引入】：当前消费端串行单消费者、回查峰值有限；待发布链路拦截（发帖/评论前
 *  getSnapshot 校验）接入后再按需引入 Caffeine，避免超前优化
 * - 覆盖式写入：SET 整个 DTO（无 read-modify-write，天然并发安全）；
 *   撤销=全空快照覆盖=同步解封，无独立 delete 分支（语义单一防漏解封）
 * - 落日志前剥离 \r\n（通用纪律③；本类日志仅落 userId 与布尔态，禁落处罚理由等 UGC）
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PunishSnapshotServiceImpl implements PunishSnapshotService {

    /** Redis 读写模板（快照 value 为 JSON 字符串） */
    private final StringRedisTemplate stringRedisTemplate;

    /** Jackson 序列化器（PunishStatusDTO 纯 DTO，直接复用 Spring 容器实例） */
    private final ObjectMapper objectMapper;

    /** 快照 TTL（秒，外置可调，默认 300；写入时 ±10% 抖动防雪崩） */
    @Value("${litisle.moderation.punish.snapshot-ttl-seconds:300}")
    private long snapshotTtlSeconds;

    /**
     * 覆盖式写入快照（B1 步骤3）：以回查权威态覆盖本地，撤销=全空快照覆盖=同步解封
     *
     * <p>Redis 写入失败向上抛非预期异常：消费端转重试/DLQ（漏写快照=漏封风险，禁静默吞错）</p>
     */
    @Override
    public void overwriteFromAuthority(PunishStatusDTO status) {
        // 步骤1：参数闸门 fail-fast——快照载荷必须带 userId，缺失属调用方误用
        if (status == null || status.getUserId() == null) {
            throw new IllegalStateException("禁言快照覆盖写入载荷非法：status/userId 为空");
        }
        Long userId = status.getUserId();
        try {
            // 步骤2：序列化快照（Jackson JSON，禁 Java 原生反序列化）
            String json = objectMapper.writeValueAsString(status);
            // 步骤3：TTL ±10% 抖动（防同批用户快照集体过期雪崩）
            long min = (long) (snapshotTtlSeconds * 0.9);
            long ttl = ThreadLocalRandom.current().nextLong(min, snapshotTtlSeconds * 2 - min);
            // 步骤4：覆盖式 SET（无 read-modify-write，天然并发安全；无 delete 分支防漏解封）
            stringRedisTemplate.opsForValue().set(
                    AuthCacheKey.PUNISH_SNAPSHOT + userId, json, Duration.ofSeconds(ttl));
            log.info("禁言快照覆盖写入成功: userId={}, banned={}, muted={}, ttlSeconds={}",
                    userId, status.getBanned(), status.getMuted(), ttl);
        } catch (Exception e) {
            // Redis 故障/序列化失败：error 留痕后向上抛（消费端 executeWithRetry 转重试/入 DLQ）
            log.error("禁言快照覆盖写入失败，userId={}", userId, e);
            throw new IllegalStateException("禁言快照覆盖写入失败，userId=" + userId, e);
        }
    }

    /**
     * 读快照（供发布链路拦截/会话校验兑底读取；Redis 直读，本地缓存待发布拦截接入后按需引入）
     *
     * @return 当前快照；无记录/读取失败返回全空快照（不返回 null，避免调用方空指针分支）
     */
    @Override
    public PunishStatusDTO getSnapshot(Long userId) {
        // 步骤1：参数闸门——无 userId 直接返回全空快照（拦截口径宁放行不误判，不抛异常阻断主流程）
        if (userId == null) {
            return emptySnapshot(null);
        }
        try {
            // 步骤2：Redis 读快照
            String json = stringRedisTemplate.opsForValue().get(AuthCacheKey.PUNISH_SNAPSHOT + userId);
            if (json != null && !json.isBlank()) {
                return objectMapper.readValue(json, PunishStatusDTO.class);
            }
            // 步骤3：无记录 → 全空快照兑底（不返回 null）
            return emptySnapshot(userId);
        } catch (Exception e) {
            // Redis 故障：error 留痕后返回全空快照——拦截场景宁暂放行不阻断登录主流程，
            // 快照陈旧窗口由 punish.result 重投/对账 sweeper 收敛
            log.error("禁言快照读取失败，兑底返回全空快照，userId={}", userId, e);
            return emptySnapshot(userId);
        }
    }

    /**
     * 构造全空快照（banned/muted 均 false，frozenUntil 空；撤销语义的兑底形态）
     */
    private PunishStatusDTO emptySnapshot(Long userId) {
        PunishStatusDTO empty = new PunishStatusDTO();
        empty.setUserId(userId);
        empty.setBanned(Boolean.FALSE);
        empty.setMuted(Boolean.FALSE);
        return empty;
    }
}
