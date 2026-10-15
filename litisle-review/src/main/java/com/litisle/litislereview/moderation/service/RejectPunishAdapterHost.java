package com.litisle.litislereview.moderation.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.litisleapi.moderation.MachineRejectPunishGate;
import com.litisle.litislereview.domain.entity.PunishLog;
import com.litisle.litislereview.domain.entity.ViolationScore;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.PunishActionEnum;
import com.litisle.litislereview.enums.PunishStatusEnum;
import com.litisle.litislereview.mapper.PunishLogMapper;
import com.litisle.litislereview.mapper.ViolationScoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 机审 REJECT 处罚落库适配器宿主（板块五 5-1，实现 {@link MachineRejectPunishGate.RejectPunishAdapter}）
 *
 * 【职责】承接契约层闸口 {@link MachineRejectPunishGate#recordRejectPunish} 委托的落库动作：
 * 同一本地事务内落 punish_log(action=3-内容下架) + violation_score 违规分原子累加。
 * 事务边界由调用方（ModerationTextService/ModerationImageService 的编程式事务）保证，
 * 本类禁止自行提交/回滚事务——必须与 moderation_record 留痕/任务 CAS 同生共死。
 *
 * 【阈值红线（5-1.2）】action=3 仅落 punish_log 流水 + violation_score.score 原子累加（展示/预警分），
 * 严禁更新 last_punish_action / frozen_until——自动禁言/封禁阈值只重放 action∈{1,2} 的生效行，
 * 机审误判在申诉裁决前不得触发真实封禁（不影响登录态）。
 *
 * 【并发兜底】violation_score 一用户一行（主键 user_id INPUT）：首次落分采用「insert →
 * 主键冲突转原子累加」两段式，避免并发首次插入竞态导致累加丢失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RejectPunishAdapterHost implements MachineRejectPunishGate.RejectPunishAdapter {

    /** 系统操作人ID（punish_log.operator_id=0-系统：机审联动非人工裁决） */
    private static final long OPERATOR_SYSTEM = 0L;

    /** 系统操作人名称（punish_log.operator_name 审计冗余列固定值） */
    private static final String OPERATOR_SYSTEM_NAME = "系统";

    /** 处罚流水 Mapper（append-only 落行专用） */
    private final PunishLogMapper punishLogMapper;

    /** 违规分窄状态表 Mapper（原子累加专用） */
    private final ViolationScoreMapper violationScoreMapper;

    /** 处罚结果事件发布器相关依赖不动，本类新增机审观测埋点组件（7-1：moderation_punish_total{action}，ObjectProvider optional 模式静默跳过） */
    private final ModerationMetrics moderationMetrics;

    /**
     * 同一事务落处罚行 + 违规分累计（5-1.1 列口径收口）
     *
     * <p> punish_log：user_id/biz_type/biz_id/review_task_id/reason/trace 口径由上下文携带；
     * action 固定 3-内容下架、operator_id 固定 0-系统、status 固定 1-生效、expire_time=null（无禁言期）。
     * violation_score：score=score+Δ 原子累加（Δ=punish_log.score 快照，供 5-4 申诉重放重算），
     * 首次插入主键冲突转累加。</p>
     *
     * @param context 处罚记账上下文（契约层闸口组装）
     * @return 新插入的 punish_log 主键 ID（供 5-2.1 事件组装）；null-落行失败（调用方中断审核事务）
     */
    @Override
    public Long recordPunish(MachineRejectPunishGate.RejectPunishContext context) {
        // 步骤1：同事务落 punish_log 行（action=3-内容下架、operator_id=0-系统、status=1-生效，
        //   MySQL记录处罚日志     score 为本次记入分值快照，落行固化 Δ 供按 status=1 行重放确定可算）
        PunishLog punishLog = new PunishLog()
                .setUserId(context.userId())
                .setAction(PunishActionEnum.CONTENT_TAKEDOWN)
                .setScore(context.score())
                .setReason(context.reason())
                .setBizType(resolveBizType(context.bizType()))
                .setBizId(context.bizId())
                .setReviewTaskId(context.reviewTaskId())
                .setExpireTime(null)
                .setOperatorId(OPERATOR_SYSTEM)
                .setOperatorName(OPERATOR_SYSTEM_NAME)
                .setStatus(PunishStatusEnum.EFFECTIVE)
                .setCreateTime(LocalDateTime.now());
        int rows = punishLogMapper.insert(punishLog);
        if (rows <= 0 || punishLog.getId() == null) {
            // 落行失败：返回 null 由闸口转 false，调用方中断审核事务（处罚记账静默丢失=违规者零分累计，宁严勿松）
            log.error("机审REJECT处罚流水落行失败 userId={} bizType={} bizId={} reviewTaskId={}",
                    context.userId(), context.bizType(), context.bizId(), context.reviewTaskId());
            return null;
        }
        // 步骤2：violation_score 违规分原子累加（仅 score 列，红线：不碰 last_punish_action/frozen_until）
                // 7-1：moderation_punish_total{action} 处罚落库计数——punish_log 落行成功后同源收口（机审/人审/申诉撤销全链路 punish_log 均落本类/同源 Mapper，无分支重复埋点）
            //检测组件处罚记录+1
        moderationMetrics.incrementPunish(punishLog.getAction());
            //mysql对应处罚积分+1
        accumulateViolationScore(context.userId(), context.score());
        // 步骤3：返回新流水 ID 供闸口组装 punish.result 事件（punishLogId 仅排障，严禁作版本门控）
        return punishLog.getId();
    }

    /**
     * violation_score 违规分原子累加（首次插入主键冲突转累加的两段式并发兜底）
     *
     * <p>setSql 采用 MyBatis-Plus {0} 参数化占位符（底层 #{} 绑定），禁字符串拼接防 SQL 注入；
     * 累加同时刷新 update_time。首插竞态说明：两请求同事务并发首插同一 user_id，
     * 后者 DuplicateKeyException 后重走累加分支，保证 Δ 不丢。</p>
     *
     * @param userId 被处罚用户 ID
     * @param delta  本次累加分值（punish_log.score 快照）
     */
    private void accumulateViolationScore(Long userId, Integer delta) {
        // 第一段：已有一行则直接原子累加（score=score+Δ，数据库行级原子性，无读改写竞态）
        if (accumulateOnce(userId, delta) > 0) {
            return;
        }
        // 第二段：无行（首次违规）则插入初始行 score=Δ（禁碰 last_punish_action/frozen_until，交由 DB 默认值 0-无）
        try {
            ViolationScore init = new ViolationScore()
                    .setUserId(userId)
                    .setScore(delta)
                    .setUpdateTime(LocalDateTime.now());
            violationScoreMapper.insert(init);
        } catch (DuplicateKeyException e) {
            // 并发首次插入竞态兜底：对方事务已插成功，本事务转原子累加（Δ 不丢）
            accumulateOnce(userId, delta);
        }
    }

    /**
     * 单次原子累加执行（update violation_score set score=score+Δ, update_time=now where user_id=?）
     *
     * @param userId 被处罚用户 ID
     * @param delta  本次累加分值
     * @return 影响行数（0=该用户尚无违规分行）
     */
    private int accumulateOnce(Long userId, Integer delta) {
        return violationScoreMapper.update(null, new LambdaUpdateWrapper<ViolationScore>()
                .eq(ViolationScore::getUserId, userId)
                .setSql("score = score + {0}", delta)
                .set(ViolationScore::getUpdateTime, LocalDateTime.now()));
    }


    /**
     * 机审 bizType 码 → punish_log.biz_type 枚举映射（ModerationConstant.BIZ_TYPE_* 口径）
     *
     * <p>未知码抛 IllegalStateException 直拦：脏 biz_type 落流水将导致 5-4 申诉重放/反查错乱，
     * 宁回滚事务勿落脏行。</p>
     *
     * @param bizType 机审业务类型码
     * @return 对应枚举
     */
    private ModerationBizTypeEnum resolveBizType(Integer bizType) {
        if (bizType == null) {
            throw new IllegalStateException("机审REJECT处罚上下文缺失 bizType，禁止落空类型流水行");
        }
        return switch (bizType) {
            case 1 -> ModerationBizTypeEnum.ARTICLE;
            case 2 -> ModerationBizTypeEnum.COMMENT;
            case 3 -> ModerationBizTypeEnum.USERNAME;
            case 4 -> ModerationBizTypeEnum.AUTHOR_APPLY;
            case 5 -> ModerationBizTypeEnum.MESSAGE;
            case 6 -> ModerationBizTypeEnum.FILE;
            default -> throw new IllegalStateException("未知机审 bizType，处罚流水落行失败：" + bizType);
        };
    }
}
