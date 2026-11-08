package com.litisle.litislereview.sweeper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.moderation.PunishAuditDTO;
import com.litisle.litisleapi.feign.AuthClient;
import com.litisle.litislereview.domain.entity.PunishLog;
import com.litisle.litislereview.enums.PunishActionEnum;
import com.litisle.litislereview.enums.PunishStatusEnum;
import com.litisle.litislereview.mapper.PunishLogMapper;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import com.litisle.litislereview.support.RedisLockReleaseSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 处罚双向对账 sweeper（板块五 5-3.2，§4.2.3；B5 供给契约的消费端）
 *
 * 定期把「审核处罚系统的权威记录」和「认证/会话系统的实时状态」做双向对账，发现不一致就自动补漏，确保用户不会被漏封、也不会被误伤。
 *
 * 【职责】punish_log（review 权威流水）为 SSOT，周期比对 auth 侧会话/快照实况，双向补漏：
 * - 正向补踢：生效封禁行（action=2 未过期，含永久）用户若仍有活跃会话 → 复用既有 forceLogout 契约补 kickout
 *   （5-2.3 即时下线事件丢失/消费失败时的兜底；漏封是安全风险，宁多补踢不漏踢）
 * - 反向补解封：近期撤销行（action∈{1,2}）用户若 auth 快照仍 banned/muted 但权威已无生效行
 *   → 调 refreshPunishSnapshot 让 auth 以 review 回查覆盖快照（漏解封是误伤；
 *   SSOT 收口：sweeper 禁任何直写快照分支，仅触发回查覆盖）
 *
 * 【调度与容错】cron 由 @Scheduled 占位符直读（litisle.moderation.reconcile.cron 外置可调，
 * 默认每 10 分钟）；Redis SETNX 互斥锁防多实例同轮重复扫描（value=实例 token 防误删他锁）；
 * 对账 Feign 端点 fail-fast 降级（绝不假空静默漏补），单用户失败仅 error 留痕不阻断整轮，下轮重扫兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PunishReconcileSweeper {

    //sweeper = 清扫工 / 扫地机

    /** 对账互斥锁 key（各段服务端派生，无用户原串拼接） */
    private static final String LOCK_KEY = "review:sweep:punish-reconcile:lock";

    /** 处罚流水 Mapper（SSOT 扫描源） */
    private final PunishLogMapper punishLogMapper;

    /** auth 契约客户端（getPunishAudit 审计回查 / forceLogout 正向补踢 / refreshPunishSnapshot 反向补解封） */
    private final AuthClient authClient;

    /** Redis 模板（仅互斥锁用途） */
    private final StringRedisTemplate stringRedisTemplate;

    /** 互斥锁原子释放工具（Lua 比对 token 后删除，消除 GET 与 DEL 之间的误删窗口） */
    private final RedisLockReleaseSupport lockReleaseSupport;

    /** 对账参数（batch-limit / revoke-lookback-days / lock-seconds 外置 yml 运营可调） */
    private final ModerationProperties properties;

    /**
     * 定时对账入口（cron 占位符直读，默认 0&#42;/10 &#42; &#42; &#42; &#42; ? 每 10 分钟一轮）
     * <p>互斥锁先行：抢不到说明他实例在扫，本轮直接跳过；finally 中仅当锁仍归属本实例才释放</p>
     */
    @Scheduled(cron = "${litisle.moderation.reconcile.cron:0 */10 * * * ?}")
    public void reconcile() {
        // 步骤1：SETNX 抢互斥锁，value=本实例 token（防误删他实例锁）
        String token = UUID.randomUUID().toString();
        ModerationProperties.Reconcile cfg = properties.getReconcile();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, token, Duration.ofSeconds(cfg.getLockSeconds()));
        if (!Boolean.TRUE.equals(locked)) {
            // 他实例持锁中，本轮跳过
            return;
        }
        try {
            // 步骤2：双向对账（先正向补踢后反向补解封，两方向互不依赖，顺序无影响）
            int kicked = reconcileForward(cfg.getBatchLimit());
            int refreshed = reconcileBackward(cfg.getBatchLimit(), cfg.getRevokeLookbackDays());
            if (kicked > 0 || refreshed > 0) {
                log.info("处罚双向对账完成：正向补踢 {} 人，反向补解封 {} 人", kicked, refreshed);
            }
        } finally {
            // 步骤3：仅当锁 value 仍为本实例 token 时释放（执行超时锁已过期被他人抢占时不误删）
            // 改为 Lua 原子执行：取值、比对、删除在 Redis 内一次完成，消除两步之间的误删窗口
            lockReleaseSupport.releaseIfOwned(LOCK_KEY, token, "处罚对账");
        }
    }

    /**
     * 正向补踢：生效封禁行用户若仍有活跃会话 → forceLogout 补 kickout
     *
     * @param batchLimit 单轮最大扫描行数（防流水膨胀拖垮调度线程）
     * @return 本轮实际补踢人数
     */
    private int reconcileForward(int batchLimit) {
        // 扫描源：status=1 生效 + action=2 封禁 + 未过期（expire_time null=永久）——权威态认定「不应存在活跃会话」
        // 用 Page 替代 .last("LIMIT")：分页拦截器标准方式，无裸 SQL 字符串（searchCount=false 跳过 COUNT）
        List<Long> userIds = punishLogMapper.selectList(Page.of(1, batchLimit, false),
                        new LambdaQueryWrapper<PunishLog>()
                                .select(PunishLog::getUserId)
                                .eq(PunishLog::getStatus, PunishStatusEnum.EFFECTIVE)
                                .eq(PunishLog::getAction, PunishActionEnum.BAN)
                                .and(w -> w.isNull(PunishLog::getExpireTime)
                                        .or().gt(PunishLog::getExpireTime, LocalDateTime.now())))
                .stream().map(PunishLog::getUserId).filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        int kicked = 0;
        for (Long userId : userIds) {
            try {
                // 步骤1：回查 auth 审计快照（活跃会话判定由 auth 收口：登录态 ∥ 设备索引非空，故障保守按活跃上报）
                PunishAuditDTO audit = unwrap(authClient.getPunishAudit(userId), userId);
                if (Boolean.TRUE.equals(audit.getHasActiveSession())) {
                    // 步骤2：补踢复用既有 forceLogout 契约（B5 口径：不新增补踢端点），幂等重复执行无害
                    authClient.forceLogout(userId);
                    kicked++;
                    log.warn("正向补踢生效：userId={}（存在生效封禁行但会话仍活跃）", userId);
                }
            } catch (Exception e) {
                // 单用户失败不阻断整轮（端点 fail-fast 抛错而非假空），error 留痕下轮重扫兜底
                log.error("正向补踢对账失败，下轮重试：userId={}", userId, e);
            }
        }
        return kicked;
    }

    /**
     * 反向补解封：近期撤销行用户若 auth 快照仍封/禁但权威已无生效行 → refreshPunishSnapshot
     *
     * 用户已经被解封/撤销处罚了，但 auth 服务的会话快照还残留着「封禁/禁言」状态，导致用户仍然被限制。
     *
     * 防止意外情况，导致 review 侧权威数据已经说「没罚了」，但 auth 侧快照还显示「有罚」→ 用户被误伤，无法正常使用。
     *  reconcileBackward 就是兜底补偿：定期扫描近期撤销行，主动发现并修复这种不一致。
     *
     * @param batchLimit         单轮最大扫描行数
     * @param revokeLookbackDays 撤销行回扫窗口（天）：窗口外残留快照靠自身 TTL（默认 300s）自愈，无需回扫
     * @return 本轮实际触发补解封人数
     */
    private int reconcileBackward(int batchLimit, int revokeLookbackDays) {
        // 扫描源：status=2 已撤销 + action∈{1,2}（仅禁言/封禁涉及会话快照；3-下架/4-警告撤销不碰快照）
        // 仅扫近窗口撤销行（revoke_time），防全表膨胀
        // 用 Page 替代 .last("LIMIT")：分页拦截器标准方式，无裸 SQL 字符串（searchCount=false 跳过 COUNT）
        List<Long> userIds = punishLogMapper.selectList(Page.of(1, batchLimit, false),
                        new LambdaQueryWrapper<PunishLog>()
                                .select(PunishLog::getUserId)
                                .eq(PunishLog::getStatus, PunishStatusEnum.REVOKED)
                                .in(PunishLog::getAction, PunishActionEnum.MUTE, PunishActionEnum.BAN)
                                .ge(PunishLog::getRevokeTime, LocalDateTime.now().minusDays(revokeLookbackDays)))
                .stream().map(PunishLog::getUserId).filter(Objects::nonNull).distinct()
                .collect(Collectors.toList());
        int refreshed = 0;
        for (Long userId : userIds) {
            try {
                PunishAuditDTO audit = unwrap(authClient.getPunishAudit(userId), userId);
                // 步骤1：本地权威比对——快照有罚但 punish_log 无对应未过期生效行 → 撤销事件丢失，快照脏
                boolean staleBanned = Boolean.TRUE.equals(audit.getSnapshotBanned())
                        && !hasEffectiveRow(userId, PunishActionEnum.BAN);
                boolean staleMuted = Boolean.TRUE.equals(audit.getSnapshotMuted())
                        && !hasEffectiveRow(userId, PunishActionEnum.MUTE);
                //stale:过期的、陈旧的、脏的
                if (staleBanned || staleMuted) {
                    // 步骤2：SSOT 收口——仅触发 auth 回查 review 权威态覆盖快照（幂等），禁直写快照分支
                    authClient.refreshPunishSnapshot(userId);
                    refreshed++;
                    log.warn("反向补解封生效：userId={}（快照有罚但权威无生效行）", userId);
                }
            } catch (Exception e) {
                log.error("反向补解封对账失败，下轮重试：userId={}", userId, e);
            }
        }
        return refreshed;
    }

    /**
     * 权威比对基准：该用户是否存在指定动作的未过期生效行（含永久）
     *
     * @param userId 用户 ID
     * @param action 处罚动作（1-禁言 / 2-封禁）
     * @return true=存在（快照显示有罚与权威一致，无需补解封）
     */
    private boolean hasEffectiveRow(Long userId, PunishActionEnum action) {
        Long count = punishLogMapper.selectCount(new LambdaQueryWrapper<PunishLog>()
                .eq(PunishLog::getUserId, userId)
                .eq(PunishLog::getStatus, PunishStatusEnum.EFFECTIVE)
                .eq(PunishLog::getAction, action)
                .and(w -> w.isNull(PunishLog::getExpireTime)
                        .or().gt(PunishLog::getExpireTime, LocalDateTime.now())));
        return count != null && count > 0;
    }

    /**
     * Result 解包 + fail-fast 校验（对账端点失败必须显式抛错进 catch 留痕，禁静默当「无异常」漏补）
     *
     * @param resp   Feign 响应
     * @param userId 用户 ID（排障日志用）
     * @return 审计快照（非 null）
     */
    private PunishAuditDTO unwrap(Result<PunishAuditDTO> resp, Long userId) {
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            throw new IllegalStateException("处罚对账审计回查失败：userId=" + userId + "，resp=" + resp);
        }
        return resp.getData();
    }
}
