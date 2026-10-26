package com.litisle.litisleapi.moderation;

import com.litisle.litisleapi.event.moderation.PunishResultEvent;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 机审 REJECT 联动处罚闸口（板块五 5-1，Review-Plan §4.2.3）——契约层骨架（实现挂起）。
 *
 * MachineRejectPunishGate 是 机审 REJECT → 处罚记账 + 事件发布​ 的唯一入口（收口），
 * 属于契约层骨架代码，不包含 ORM / MQ 实现，只定义流程与红线规则。
 *
 * <p>【职责边界】本类是「机审 REJECT 之后处罚联动」的唯一收口：文本（3-1）/图片（3-2）消费端
 * 判 REJECT 时，在同一本地事务内落处罚流水 + 违规分累计。内容下架语义本身由 result 事件
 * 消费端（content/file 侧）处理，本类只负责「处罚记账」，两件事解耦、单一职责
 * （模式同 {@link UsernameRejectGate}：闸口做编排，列口径由宿主适配器收口，契约层零 ORM 依赖）。</p>
 *
 * <p>【5-1.1 同事务落行】REJECT 同一本地事务内：落 punish_log(action=3-内容下架,
 * operator_id=0-系统, score=本次记入分值快照, status=1-生效) + violation_score 原子累加
 * （score=score+Δ，落行前 punish_log.score 快照已固化 Δ；首次插入主键冲突转原子累加）。
 * 事务边界由调用方（ModerationTextService/ModerationImageService 的编程式事务）保证，
 * 本闸口不自行开事务——必须与 moderation_record 留痕/任务 CAS 同生共死，禁止拆分。</p>
 *
 * <p>【5-1.2 阈值重放口径（红线）】禁言/封禁阈值判定只重放 punish_log 中 action∈{1,2}
 * （1-禁言/2-封禁）且 status=1-生效 的行；action=3 仅落流水、只进展示/预警分，不计入阈值、
 * 不更新 violation_score.last_punish_action / frozen_until（不影响登录态）。
 * 依据：若 action=3 误计入阈值→机审误判在申诉裁决前就触发真实封禁，误伤放大；
 * 反向：若机审驳回完全不处罚→违规者零分累计可低成本重复违规，违背「机审挡量、人审兜疑」漏斗设计。</p>
 *
 * <p>【实现落位】方法体已随板块五 5-1/5-2.1 落地；嵌入点为
 * litisle-review ModerationTextService.persistAndPublish（3-1 文本 REJECT）与
 * ModerationImageService.persistSingleImage / 配图复审聚合 REJECT（3-2 图片 REJECT）。
 * 物理发送通道（MqSendUtil）不在契约层依赖内，以 {@code Consumer<PunishResultEvent>}
 * 由宿主注入（review 侧统一收敛到 PunishResultPublisher），契约层仅负责
 * 「事件组装 + afterCommit 注册 + 失败语义声明」的编排收口。</p>
 *
 * <p>【5-2.1 发布端收口】处罚落行成功后，在同一事务流程内组装 PunishResultEvent
 * （litisle-api event/moderation 包，auth B 组消费端已备：队列常量
 * QUEUE_AUTH_MODERATION_PUNISH / 路由 ROUTING_MODERATION_PUNISH 已在 MqConstant 声明）：
 * 本地事务提交 → afterCommit 中 sendCritical 发 punish.result → 发布失败转 IllegalStateException
 * 入 DLQ 兑底（事务顺序铁律：落库失败不发事件，提交后发送防脏事件）。
 * 机审联动事件体口径：action=3-内容下架、op=1-生效、punishLogId=新落流水行 ID
 * （仅排障，严禁作版本门控）、frozenUntil=null（action=3 无禁言期）、traceId=MDC。
 * 事件体不含 reason（处罚 reason 为 UGC 关联字段，消费端按 punishLogId 回查，防事件体膨胀）。</p>
 *
 * @author litisle
 */
public final class MachineRejectPunishGate {

    private MachineRejectPunishGate() {
    }

    /**
     * 机审 REJECT 处罚记账（5-1.1）：同事务落 punish_log(action=3) + violation_score 累计。
     *
     * 作用：机审判 REJECT 后，把「谁、因哪条内容、记多少分」固化成处罚流水与违规分累加，
     * 供 5-1.2 阈值重放与 5-4 申诉改判重算（按 status=1 生效行 append-only 重放，确定可算）。
     *
     * @param context     处罚记账上下文（userId/分值快照/留痕口径，见 RejectPunishContext 注）
     * @param adapter     处罚落库适配（review 侧注入：punish_log + violation_score 列口径见 RejectPunishAdapter 注）
     * @param eventSender 物理发送通道（宿主注入：review 侧统一收敛到 PunishResultPublisher；
     *                    契约层无 MQ 依赖，仅负责发布编排）
     * @return true-记账成功（afterCommit 已注册，发布失败由事务提交调用方收 IllegalStateException）；
     *         false-落库失败（调用方按口径中断审核事务或仅留痕）
     */
    public static boolean recordRejectPunish(RejectPunishContext context, RejectPunishAdapter adapter,
                                             Consumer<PunishResultEvent> eventSender) {
        // 步骤1：适配器/发送通道判空 fail-fast（处罚记账静默丢失=违规者零分累计，宁严勿松）
        if (adapter == null || eventSender == null) {
            throw new IllegalArgumentException("RejectPunishAdapter/eventSender 为空，禁止静默跳过机审 REJECT 处罚记账");
        }
        // 步骤2：入参 fail-fast（userId/score 快照缺失均属调用方误用，直拦防脏流水行）
        if (context == null || context.userId() == null || context.score() == null) {
            throw new IllegalArgumentException("RejectPunishContext 缺失 userId/score，禁止落空处罚流水行");
        }
        // 步骤3：委托适配器在同一事务落行（列口径见 RejectPunishAdapter.recordPunish 注），
        //        返回新流水 punishLogId；null=落库失败，由调用方决定事务走向
        Long punishLogId = adapter.recordPunish(context);
        if (punishLogId == null) {
            return false;
        }
        // 步骤4（5-2.1）：组装事件体——action=3-内容下架、op=1-生效、punishLogId 仅排障
        //        （严禁作版本门控，撤销就地改同一行 id 不变）、frozenUntil=null（action=3 无禁言期）
        PunishResultEvent event = new PunishResultEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventTime(LocalDateTime.now());
        event.setTraceId(context.traceId());
        event.setVersion(1);
        event.setUserId(context.userId());
        event.setAction(PunishResultEvent.ACTION_CONTENT_TAKEDOWN);
        event.setOp(PunishResultEvent.OP_EFFECTIVE);
        event.setPunishLogId(punishLogId);
        event.setFrozenUntil(null);
        // 步骤5（事务顺序铁律）：本地事务提交后才物理发送（afterCommit），杜绝脏事件——
        //        落库失败/回滚则不发送；afterCommit 内抛出的 IllegalStateException 会传播到
        //        事务提交调用方→消费端不 ACK 入 DLQ 兑底（消息不丢，延迟重试走 hash 幂等闭环）
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 防误用：调用方必须保证本闸口在编程式事务内调用（与 moderation_record 留痕同生共死）
            throw new IllegalStateException("recordRejectPunish 必须在活动事务内调用（与留痕/CAS 同生共死），禁止无事务使用");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    eventSender.accept(event);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("处罚结果事件发布失败（事务已提交），转 DLQ 兑底，punishLogId="
                            + punishLogId + "，userId=" + context.userId(), e);
                }
            }
        });
        return true;
    }

    /**
     * 机审 REJECT 处罚记账上下文（record 不可变值对象，风格对齐 UsernameRejectGate.RejectRecordContext）。
     *
     * @param userId       被处罚用户 ID（文本按回查 DTO 归属解析 / 图片按 file_meta 归属解析）
     * @param bizType      机审业务类型（ModerationConstant.BIZ_TYPE_*）
     * @param bizId        业务内容 ID（文章/评论/私信/文件）
     * @param scene        审核场景（ARTICLE/COMMENT/MESSAGE/IMAGE/ARTICLE_IMG_RE_REVIEW）
     * @param reviewTaskId 关联审核任务 ID（单图场景无任务置 null，留痕口径对齐 moderation_record）
     * @param contentHash  内容指纹（SHA256 派生，服务端口径）
     * @param score        本次记入的违规分值快照（落行固化 Δ，供 5-4 重放重算确定可算）
     * @param reason       处罚原因（机审 reason 卫生化后口径：剥离换行 + 截断，防 CRLF 伪造日志）
     * @param traceId      全链路追踪 ID（MDC 口径）
     */
    public record RejectPunishContext(Long userId, Integer bizType, Long bizId, String scene,
                                      Long reviewTaskId, String contentHash, Integer score,
                                      String reason, String traceId) {
    }

    /**
     * 处罚落库适配（review 侧注入，契约层零 ORM 依赖；模式同 UsernameRejectGate.RecordAdapter）。
     *
     * `recordPunish` 要做的事是：写`punish_log` 表 + 累加`violation_score` 表
     * —— 这两张表是 review 的数据库、MyBatis Mapper、实体类全在 review 模块里 。api 模块根本没有这些类，物理上不可能实现。
     * 这就是经典的 端口-适配器模式 ：
     * - api 定义「端口」（接口）：我需要一个「能落处罚行的能力」，口径见 Javadoc（action=3、operator=0、status=1、禁更新 frozen_until……）
     * - review 提供「适配器」（实现）：`RejectPunishAdapterHost` 用自己的 Mapper 把口径落地
     * 
     * 为什么review实现了此接口？litislereview.moderation.service-RejectPunishAdapterHost
     * 不是「api 使用 review 的实现」，而是 review 自己用，且是 当参数传进去的
     * 落库需要 punish_log/violation_score 的 Mapper 和实体，全在 review；依赖方向 review→api，api 物理上无法实现
     * 如果没有实现此接口，SpringBoot会启动失败 —— （构造器注入找不到 Bean）；闸口内的判空异常是防手工调用绕过容器的第二道保险
     * 
     * @FunctionalInterface 标记这个接口是「函数式接口」——只能有一个抽象方法，可以被 Lambda 表达式或方法引用直接实现。
     */
    @FunctionalInterface
    public interface RejectPunishAdapter {

        /**
         * 同一事务落处罚行 + 违规分累计（调用方须已在本地事务内，本方法禁止自行提交/回滚事务）。
         *
         * <p>列口径——punish_log：user_id=context.userId()、action=3-内容下架、
         * operator_id=0-系统、score=context.score()（分值快照）、status=1-生效、
         * reason=context.reason()、trace_id=context.traceId()；
         * violation_score：update set score=score+context.score() where user_id=?（原子累加，
         * 首次插入主键冲突转累加；禁  version 乐观锁，重算依据在 punish_log 流水）。
         * 红线（5-1.2）：本方法严禁更新 violation_score.last_punish_action / frozen_until——
         * 阈值判定只重放 action∈{1,2} 的 status=1 生效行，action=3 仅落流水不计入。</p>
         *
         * <p>【5-2.1 事件发布口径】落行成功后须返回新流水 punishLogId（供发布端组装事件），
         * 发布编排在闸口方法步骤4/5收口（见类注【5-2.1 发布端收口】），适配器只管落行不管发 MQ；
         * 人审 decideTask 的 REJECT/封禁联动禁止另起发布分支，须复用同一发布口径防口径漂移。</p>
         *
         * @param context 处罚记账上下文
         * @return 新插入的 punish_log 主键 ID（供事件组装）；null-落库失败
         */
        Long recordPunish(RejectPunishContext context);
    }
}
