package com.litisle.litislereview.sweeper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litislereview.domain.entity.Appeal;
import com.litisle.litislereview.domain.entity.ModerationRecord;
import com.litisle.litislereview.enums.AppealStatusEnum;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.VerdictEnum;
import com.litisle.litislereview.mapper.AppealMapper;
import com.litisle.litislereview.mapper.ModerationRecordMapper;
import com.litisle.litislereview.moderation.config.ModerationProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 违规图隔离清理 sweeper（板块六 6-3，§4.4.5 隔离保留期收口）
 *
 * QuarantineSweeper 是一个定时补偿任务，负责扫描“超期且未被申诉推翻”的违规隔离图片，限速、
 * 安全地委派 file 服务物理删除，并通过 Redis 互斥锁和 Micrometer 指标保证多实例安全与可观测性。
 *
 * 【职责】隔离图（safe_flag=0，quarantine/ 前缀）仅作审核证据暂存，超保留期
 * （litisle.moderation.quarantine.retention-days）后由本 sweeper 委派 file 侧
 * FileClient.deleteQuarantined 物理删除（对象进 SeaweedFS 回收站兜底 7 天 +
 * 业务软删 del_flag=1）——隔离图不能永久占存储，也不能被 file 侧常规清理误删
 *
 * 【为什么扫描源是 moderation_record 而非 file_meta】
 * - 保留期是审核语义（审核侧决定证据存多久），SSOT 收口本 sweeper；
 * - file 侧 7 天 delete_time 清理对 quarantine/ 前缀豁免（防两套清理打架，
 *   file 侧只响应本 sweeper 的显式删除指令）；
 * - 留痕行 biz_type=6 + verdict=REJECT 天然带 fileId（biz_id）与判定时间
 *
 * 【保留期口径】以该图最新留痕行为准：最新行仍 REJECT 且超期才候选——
 * 复审/改判后的新留痕行自然覆盖旧 REJECT 行（改判 PASS 已发 unquarantine 恢复，
 * 不应再删）；候选扫描两步走（先超期 REJECT 行 → 再 in 查询候选图全量行内存分组
 * 判定最新态），batch-limit 控候选规模防 N+1
 *
 * 【申诉豁免顺延】候选图存在 status=0 待裁决申诉 → 本轮跳过（保留期顺延）：
 * 裁决维持后下轮正常删除，改判 PASS 后 unquarantine 恢复（删除分支天然不再命中）；
 * 豁免集合一次 in 查询（防逐条 N+1）
 *
 * 【限速】批内按 delete-rate-per-minute 均摊 sleep，防批量物理删除打满 SeaweedFS
 * （回收站写入同样是 IO）；天级 cron（03:00，与 file 侧 02:00 常规清理错峰）
 *
 * 【失败语义】FileClient.deleteQuarantined 降级 fail-fast：单条失败 error 留痕
 * 不阻断整轮，下轮重扫兜底；删除动作幂等（file 侧前缀校验 + del_flag 已删跳过）
 *
 * 【Gauge】moderation.quarantine.backlog{stage=expired|appeal-held} 惰性注册
 * AtomicLong（对齐 ReviewTaskSweeper 惯例）：expired=超期待删行数（保留期已到），
 * appeal-held=申诉豁免顺延行数（防「申诉期被清理」误告警）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuarantineSweeper {

    /** 清扫互斥锁 key（各段服务端派生，无用户原串拼接，对齐 ReviewTaskSweeper 纪律） */
    private static final String LOCK_KEY = "review:sweep:quarantine:lock";

    /** 隔离积压 Gauge 指标名（stage 标签区分，Prometheus 渲染 moderation_quarantine_backlog） */
    private static final String METRIC_QUARANTINE_BACKLOG = "moderation.quarantine.backlog";

    /** 隔离积压 Gauge 的 stage 标签 key 及取值（expired=超期待删 / appeal-held=申诉豁免顺延） */
    private static final String TAG_STAGE = "stage";
    private static final String STAGE_EXPIRED = "expired";
    private static final String STAGE_APPEAL_HELD = "appeal-held";

    /** 机审留痕 Mapper（6-3.1 扫描源：biz_type=6 且 REJECT 超期的留痕行） */
    private final ModerationRecordMapper moderationRecordMapper;

    /** 申诉单 Mapper（6-3.1 豁免查询：候选图是否挂有待裁决申诉） */
    private final AppealMapper appealMapper;

    /** file 契约客户端（6-3.2 删除委派；fallback fail-fast，失败异常穿透由本 sweeper 留痕兜底） */
    private final FileClient fileClient;

    /** 清扫参数（retention-days / batch-limit / delete-rate-per-minute 外置 yml 运营可调） */
    private final ModerationProperties properties;

    /** Redis 模板（仅互斥锁用途） */
    private final StringRedisTemplate stringRedisTemplate;

    /** Micrometer 注册中心（optional 模式：未接 actuator 静默跳过，对齐 ReviewTaskSweeper 惯例） */
    private final ObjectProvider<MeterRegistry> registryProvider;

    // ===== 6-3.3 Gauge 值容器：每轮刷新，Gauge 惰性注册绑定（抓取不打 DB） =====

    /** 积压计数：stage=expired（超期未删候选行数） */
    private final AtomicLong backlogExpired = new AtomicLong(0);

    /** 积压计数：stage=appeal-held（申诉豁免顺延行数） */
    private final AtomicLong backlogAppealHeld = new AtomicLong(0);

    /**
     * 天级清扫入口（cron 占位符直读，03:00 与 file 侧 02:00 常规清理错峰；多实例互斥锁先行）
     */
    @Scheduled(cron = "${litisle.moderation.quarantine.sweep-cron:0 0 3 * * ?}")
    public void sweepDaily() {
        // ===== 步骤0：SETNX 抢互斥锁（token 防误删他锁，finally 比对归属释放，对齐 ReviewTaskSweeper） =====
        String token = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, token, Duration.ofSeconds(properties.getQuarantine().getLockSeconds()));
        if (!Boolean.TRUE.equals(locked)) {
            // 他实例持锁中，本轮跳过
            return;
        }
        try {
            // ===== 步骤1~4：候选扫描 → 申诉豁免 → 限速删除 → Gauge 刷新（6-3.1~6-3.3 主编排） =====
            int deleted = deleteExpiredQuarantined();
            if (deleted > 0) {
                log.info("违规图隔离清扫完成：本轮删除={}", deleted);
            }
        } catch (Exception e) {
            // 整轮失败仅 error 留痕，下轮重扫兜底（清扫为补偿型任务，宁晚勿断）
            log.error("违规图隔离清扫整轮失败（下轮重扫兜底）", e);
        } finally {
            // 仅当锁仍归属本实例（token 匹配）才释放，防超时后误删他实例新锁
            releaseLock(token);
        }
    }

    /**
     * 超期候选扫描 → 最新态过滤 → 申诉豁免 → 限速删除（6-3.1/6-3.2/6-3.3 主编排）
     *
     * 扫描超期且未被申诉推翻的违规隔离图片，限速、幂等地委派 file 服务物理删除，并刷新积压指标。
     * 超期：这张图片被判定为违规、放进隔离区（quarantine）之后，已经超过了配置的"保留天数"（retention-days），审核证据不再需要继续存着了，可以删了。
     *
     * <p>单条失败 error 留痕不阻断整轮，下轮重扫兜底；删除动作幂等
     * （file 侧前缀校验 + del_flag 已删跳过，重投/重扫安全）</p>
     *
     * @return 本轮实际删除数
     */
    private int deleteExpiredQuarantined() {
//        阶段一：找出"超期且当初被判违规"的留痕行
//        1. 先查第一批候选
//        2. 去重：一张图可能有多条留痕
//        3. 查这些图的全部留痕，取"最新状态"

//        阶段二：申诉豁免（有申诉的图先不删）
//        4. 查候选图里有没有"待裁决的申诉"
//（用户正在申诉中，你这边把证据图删了 → 申诉裁决时没图可看 → 不公平。）
//        5. 最终要删除的列表
//（从 stillRejected 里 剔除掉 held 里的，剩下的就是 toDelete：
//        超期 + 最新仍违规 + 没有待裁决申诉 = 可以删）

//        阶段三：限速逐条删除
//        6. 按速率限制，一条一条调 file 服务
//            遍历 toDelete，每条之间按 60000/rate 毫秒 sleep 限流，防止打爆 SeaweedFS。
//            调用 fileClient.deleteQuarantined(fileId)：
//            file 侧执行物理删除 + 回收站兜底 7 天 + 业务软删
//              容错：
//            单条失败 → error 日志，跳过，下轮重扫兜底（删除幂等）
//            线程中断 → 恢复中断位，终止本轮，剩余候选下轮处理

//        阶段四：刷新指标 + 返回结果
//        7. 刷新 Gauge 指标
//        8. 返回本轮实际删除数

        ModerationProperties.Quarantine cfg = properties.getQuarantine();
        // ===== 步骤1：扫超期 REJECT 留痕行（biz_type=FILE，create_time < now-retention-days，限批防全量扫描） =====
        List<ModerationRecord> expiredRows = moderationRecordMapper.selectList(new LambdaQueryWrapper<ModerationRecord>()
                .select(ModerationRecord::getBizId)
                .eq(ModerationRecord::getBizType, ModerationBizTypeEnum.FILE)
                .eq(ModerationRecord::getVerdict, VerdictEnum.REJECT)
                .lt(ModerationRecord::getCreateTime, LocalDateTime.now().minusDays(cfg.getRetentionDays()))
                .orderByAsc(ModerationRecord::getCreateTime)
                .last("LIMIT " + cfg.getBatchLimit()));
        if (expiredRows.isEmpty()) {
            refreshGauges(0, 0);
            return 0;
        }
        // 候选图 bizId 去重集合（一图可能多行 REJECT 留痕，同轮只删一次）
        Set<Long> candidates = expiredRows.stream()
                .map(ModerationRecord::getBizId).collect(Collectors.toCollection(LinkedHashSet::new));
        // ===== 步骤1.2：in 查候选图全量留痕行，按 bizId 分组取最新行——最新行仍 REJECT 才保留候选
        //（复审/申诉改判后的新留痕行自然覆盖旧 REJECT 行，改判 PASS 已 unquarantine 恢复，不应再删） =====
        List<ModerationRecord> allRows = moderationRecordMapper.selectList(new LambdaQueryWrapper<ModerationRecord>()
                .select(ModerationRecord::getBizId, ModerationRecord::getVerdict,
                        ModerationRecord::getCreateTime, ModerationRecord::getId)
                .eq(ModerationRecord::getBizType, ModerationBizTypeEnum.FILE)
                .in(ModerationRecord::getBizId, candidates));
        Map<Long, ModerationRecord> latestByBizId = new HashMap<>();
        for (ModerationRecord row : allRows) {
            ModerationRecord cur = latestByBizId.get(row.getBizId());
            boolean newer = cur == null
                    || row.getCreateTime().isAfter(cur.getCreateTime())
                    || (row.getCreateTime().equals(cur.getCreateTime()) && row.getId() > cur.getId());
            if (newer) {
                latestByBizId.put(row.getBizId(), row);
            }
        }
        Set<Long> stillRejected = latestByBizId.values().stream()
                .filter(r -> r.getVerdict() == VerdictEnum.REJECT)
                .map(ModerationRecord::getBizId)
                .collect(Collectors.toSet());
        if (stillRejected.isEmpty()) {
            // 候选图均已被新留痕行覆盖（复审/改判），无待删项
            refreshGauges(0, 0);
            return 0;
        }
        // ===== 步骤2：申诉豁免顺延——一次 in 查询候选图待裁决申诉（防逐条 N+1），命中者本轮跳过 =====
        Set<Long> held = appealMapper.selectList(new LambdaQueryWrapper<Appeal>()
                        .select(Appeal::getBizId)
                        .eq(Appeal::getStatus, AppealStatusEnum.PENDING)
                        .eq(Appeal::getBizType, ModerationBizTypeEnum.FILE)
                        .in(Appeal::getBizId, stillRejected))
                .stream().map(Appeal::getBizId).collect(Collectors.toSet());
        List<Long> toDelete = stillRejected.stream()
                .filter(id -> !held.contains(id)).collect(Collectors.toList());
        // ===== 步骤3：限速逐条委派 file 侧删除（批内按 60000/rate 毫秒均摊 sleep，防批量物理删除打满 SeaweedFS）；
        //  fallback fail-fast：resp 失败/异常均单条 error 留痕不阻断，下轮重扫兜底 =====
        long intervalMs = cfg.getDeleteRatePerMinute() > 0 ? 60000L / cfg.getDeleteRatePerMinute() : 0;
        int deleted = 0;
        for (int i = 0; i < toDelete.size(); i++) {
            Long bizId = toDelete.get(i);
            if (i > 0 && intervalMs > 0) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException ie) {
                    // 中断恢复中断位后终止本轮，剩余候选下轮重扫兜底
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Result<Void> resp = fileClient.deleteQuarantined(String.valueOf(bizId));
                if (resp == null || !resp.isSuccess()) {
                    log.error("隔离图删除委派失败（下轮重扫兜底），fileId={}，resp={}", bizId, resp);
                    continue;
                }
                deleted++;
            } catch (Exception e) {
                log.error("隔离图删除异常（下轮重扫兜底），fileId={}", bizId, e);
            }
        }
        // ===== 步骤4：Gauge 刷新（expired=超期未删剩余候选数，appeal-held=申诉豁免顺延数） =====
        refreshGauges(toDelete.size() - deleted, held.size());
        return deleted;
    }

    /**
     * Gauge 刷新 + 惰性注册：moderation.quarantine.backlog{stage=expired|appeal-held}
     * <p>绑定 AtomicLong 由本轮定时刷新（Prometheus 抓取不打 DB）；未接 actuator 静默跳过，
     * 同名同标签重复注册幂等（Micrometer 忽略重复注册）</p>
     *
     * @param expired    超期未删剩余候选数
     * @param appealHeld 申诉豁免顺延数
     */
    private void refreshGauges(long expired, long appealHeld) {
        backlogExpired.set(expired);
        backlogAppealHeld.set(appealHeld);
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        ///Gauge 不能“自动”更新数据。 它只能“自动”去读你给它的那个对象里的值。
        //它只能“自动”去读你给它的那个对象里的值。:Gauge 不会自己"算"任何东西，它只是——Prometheus 来问的时候，帮你去"问一下"你提前交给它的那个对象："你现在是多少？"
        Gauge.builder(METRIC_QUARANTINE_BACKLOG, backlogExpired, AtomicLong::get)
                .tag(TAG_STAGE, STAGE_EXPIRED).description("违规图隔离积压（超期待删）").register(registry);
        Gauge.builder(METRIC_QUARANTINE_BACKLOG, backlogAppealHeld, AtomicLong::get)
                .tag(TAG_STAGE, STAGE_APPEAL_HELD).description("违规图隔离积压（申诉豁免顺延）").register(registry);
    }

    /**
     * 释放互斥锁（GET 比对 token 归属后才 DEL，防误删他实例锁；异常仅 warn 留痕由锁 TTL 自愈）
     *
     * @param token 本实例锁 token
     */
    private void releaseLock(String token) {
        try {
            if (token.equals(stringRedisTemplate.opsForValue().get(LOCK_KEY))) {
                stringRedisTemplate.delete(LOCK_KEY);
            }
        } catch (Exception e) {
            // 释放异常仅 warn 留痕，由锁 TTL 过期自愈
            log.warn("隔离清扫互斥锁释放失败（TTL 自愈），key={}", LOCK_KEY, e);
        }
    }
}
