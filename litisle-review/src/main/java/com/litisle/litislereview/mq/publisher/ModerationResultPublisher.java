package com.litisle.litislereview.mq.publisher;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commonmq.send.MqSendUtil;
import com.litisle.litisleapi.constant.MqConstant;
import com.litisle.litisleapi.event.moderation.ModerationResultEvent;
import com.litisle.litislereview.domain.entity.Report;
import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.ReportStatusEnum;
import com.litisle.litislereview.enums.TargetTypeEnum;
import com.litisle.litislereview.mapper.ReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 机审结果事件统一发布器（板块三 3-4，§4.5.2 占位骨架——暂不实现，方法体只留步骤注释）
 *
 * 【落位说明】按 next_update.md §3-4 落位 review 模块 mq/publisher 包（细案指定），
 * 与 moderation 包（consumer/cache/service）平级：发布器是 MQ 出口基建，
 * 被 ModerationTextService / ModerationImageService / 后续 DLQ 补发共用，故独立成包收敛。
 *
 * 【事件体纪律】ModerationResultEvent（litisle-api 已备）：
 * {bizType, bizId, scene, contentHash, wordlibVer, reviewTaskId, verdict, category, reason, traceId}
 * ——不含正文/图片地址（防大报文与敏感数据过 MQ）。
 *
 * 【实现时计划依赖（暂不注入）】
 * - MqSendUtil（common-mq）：confirm 模式发送 + traceId MDC 透传
 * - ReportMapper：举报关联任务同事务回写（步骤4）
 * - MqConstant 路由键补充：现仅有 ROUTING_MODERATION_RESULT 单键（file 侧消费），
 *   实现时需新增 moderation.text.result / moderation.message.result / moderation.image.result
 *   三路由键及对应队列常量（与 3-1.2 ReviewRabbitConfig 拓扑声明口径对齐，防 PRECONDITION_FAILED）
 *
 * 【事务顺序铁律（3-4.3）】本地事务提交 → publisher confirm 发 result → basicAck；
 * 任何一步失败抛异常不 ACK 走重投。3-1 步骤4「hash 一致补发 result」幂等闭环依赖此顺序，写反即断裂。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationResultPublisher {

    /** 系统处理人ID（report.handler=0-系统：机审终审非人工裁决；人审 decide 回写走 ReviewTaskService，禁用本值） */
    private static final long HANDLER_SYSTEM = 0L;

    /** report.result 列长度上限（实体 @Size(max=200)，回写摘要落库前截断） */
    private static final int RESULT_MAX_LEN = 200;

    /** 判定理由发布前截断上限（3-4.1：reason 为 LLM 透传不可信输出） */
    private static final int REASON_MAX_LEN = 500;

    /** MQ 发送工具（common-mq：必达重试 + traceId MDC 透传） */
    private final MqSendUtil mqSendUtil;

    /** 举报 Mapper（举报关联任务同事务回写专用） */
    private final ReportMapper reportMapper;

    /**
     * 发布机审结果事件：按 bizType 路由分发到对应 result 队列
     *
     * @param event 机审结果事件体（verdict/category 为不可信输出已由适配器白名单校验；
     *              reason 落事件前须截断 500 + 剥离 \r\n，防 CRLF 伪造 MQ 消费端日志行）
     */
    public void publish(ModerationResultEvent event) {
        // 步骤1（3-4.1）：路由分发——bizType=5(MESSAGE)→moderation.message.result；
        //        文本 bizType∈{1,2}→moderation.text.result；图片 bizType=6→moderation.image.result；
        //        bizType=4(作家申请)无业务方回写映射，走 text.result 由 review 自己消化（无 result 消费者）
        String routingKey = resolveRoutingKey(event.getBizType());

        // 步骤2（3-4.2）：口径锁定——contentHash 必须是本次实际送审的指纹（非编辑后新值）；
        //        wordlibVer 取判定时的词库版本快照——二者错填则 content 侧 last-hash 免审键落位错误，
        //        免审机制失效（4-3.1 以 {wordlibVer}:{bizType}:{scene}:{bizId} 组键）
        //        （口径由调用方组装事件体时保证，本方法不做二次加工）

        // 步骤2.1（3-4.1 卫生纪律）：reason 为 LLM 透传不可信输出——剥离 \r\n 防 CRLF 伪造消费端日志行 + 截断 500
        event.setReason(sanitizeReason(event.getReason()));

        // 步骤3（3-4.3）：事务顺序铁律——调用方保证在本地事务提交后（afterCommit）调用本方法；
        //        MqSendUtil confirm 成功才返回，失败抛异常→消费端不 ACK 走延迟重试
        try {
            // sendCritical 必达语义：重试耗尽抛 BizException(MQ_SEND_FAILED)——但 MqAckHelper 会把
            // BizException 按业务性失败 ack，事件将静默丢失，故转 IllegalStateException 走非预期分支
            // →nack 入 DLQ 兑底（消息不丢，延迟重试）
            mqSendUtil.sendCritical(MqConstant.EXCHANGE_MODERATION, routingKey, event);
        } catch (BizException e) {
            throw new IllegalStateException("审核结果事件发布失败，转 DLQ 兑底，routingKey=" + routingKey
                    + "，bizType=" + event.getBizType() + "，bizId=" + event.getBizId(), e);
        }
    }

    /**
     * 尽力而为补发 result 事件（已处理任务重投的对账性质分流专用，3-1 步骤4 幂等闭环）。
     *
     *
     * 这是给“已经处理过、但因任务重投需要对账”的场景，补发一条 ModerationResultEvent 的轻量方法——不保证必达，只保证不炸。
     * <p>与 {@link #publish} 的差异：首次处理已发过本事件，重投属对账性质，用 send() 尽力而为
     * 而非 sendCritical；事件组装口径（contentHash/wordlibVer 锁定）同样由调用方保证</p>
     *
     * @param event 待补发的结果事件体
     */
    public void republish(ModerationResultEvent event) {
        event.setReason(sanitizeReason(event.getReason()));
        mqSendUtil.send(MqConstant.EXCHANGE_MODERATION, resolveRoutingKey(event.getBizType()), event);
    }

    /**
     * 按 bizType 解析 result 事件路由键（3-4.1 路由分发表）
     *
     * @param bizType 审核业务类型码
     * @return 对应路由键；bizType 缺失/未知抛 IllegalStateException（转 DLQ 延迟重试）
     */
    private String resolveRoutingKey(Integer bizType) {
        if (bizType == null) {
            throw new IllegalStateException("result 事件缺失 bizType，无法路由分发");
        }
        return switch (bizType) {
            case 5 -> MqConstant.ROUTING_MODERATION_MESSAGE_RESULT;
            case 1, 2, 4 -> MqConstant.ROUTING_MODERATION_TEXT_RESULT;
            case 6 -> MqConstant.ROUTING_MODERATION_IMAGE_RESULT;
            default -> throw new IllegalStateException("未知 bizType，result 事件路由失败：" + bizType);
        };
    }

    /**
     * reason 卫生处理（3-4.1）：剥离 \r\n（防 CRLF 伪造 MQ 消费端日志行）+ 截断 500
     *
     * 把 LLM 输出的 reason 做成「日志安全 + 长度可控」的干净字符串：去掉回车换行、防止日志注入、超长截断。
     * eg:下述内容可以通过换行符伪造日志
     * 违规内容\nINFO [MQ消费成功] userId=admin action=grantAdmin
     *
     * @param reason 原始判定理由（LLM 不可信输出）
     * @return 处理后的安全理由
     */
    private String sanitizeReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return reason;
        }
        String cleaned = reason.replace("\r", "").replace("\n", " ");
        return cleaned.length() <= REASON_MAX_LEN ? cleaned : cleaned.substring(0, REASON_MAX_LEN);
    }

    /**
     * 举报关联任务同事务回写（3-4.4）：机审终审落库的同一本地事务内回写举报状态
     *
     * <p>仅当 review_task 被 report.review_task_id 引用（举报触发的任务）时调用，
     * 须由消费端在三分态落库事务内调用（非 afterCommit），与任务流转原子提交</p>
     *
     * @param reviewTaskId 审核任务ID
     * @param resultSummary 回写结果摘要（机审 verdict+category 拼装，截断至 report.result 列长）
     * @return 回写举报行数（0=该任务非举报触发，正常）
     */
    public int writebackReportsOnFinalVerdict(Long reviewTaskId, String resultSummary) {
        // 步骤4（3-4.4）：update report set status=3-已裁决, handler=0-系统, result=摘要
        //        where review_task_id=? and status<3（活跃态过滤）
        //        ★status<3 必带：1 行任务对多轮多份 report 是 1:N，禁裸 review_task_id 全量 UPDATE
        //        （覆盖历史轮 result/abuse_flag = 抹掉诬告反制证据链）
        //        ★handler=0-系统：机审终审非人工裁决，人审 decide 的举报回写走 ReviewTaskService（decideTask）

        // 注意：本方法只回写 status=3；abuse_flag 判定属人审 decide 职责（「明确无依据/明显虚假」才置1），机审不判诬告
        // 摘要截断至 report.result 列长（@Size(max=200)），防超长回写中断事务
        String summary = resultSummary == null ? null
                : (resultSummary.length() <= RESULT_MAX_LEN ? resultSummary : resultSummary.substring(0, RESULT_MAX_LEN));
        Report patch = new Report()
                .setStatus(ReportStatusEnum.DECIDED)
                .setHandler(HANDLER_SYSTEM)
                .setResult(summary);
        // 返回回写行数（0=该任务非举报触发，正常），由消费端记日志对账，不做业务判定
        return reportMapper.update(patch, new LambdaUpdateWrapper<Report>()
                .eq(Report::getReviewTaskId, reviewTaskId)
                .lt(Report::getStatus, ReportStatusEnum.DECIDED));
    }

    /**
     * bizType→target_type 映射（3-4.5）：反查举报方向（biz→target）专用
     *
     * <p>映射表禁恒等：1-文章↔1、2-评论↔2、3-用户名↔3 同值；5(私信)↔4、6(图片)↔5 错位；
     * 4-作家申请无映射（返回 null，调用方不反查举报）。
     * 建任务（target→biz）与反查举报（biz→target）两方向都必须走本映射——
     * 恒等实现使反查举报零行→举报永久卡 status=2、uk_content_active 不释放</p>
     *
     * @param bizType 审核业务类型
     * @return 对应举报 target_type；无映射返回 null
     */
    public TargetTypeEnum resolveTargetType(ModerationBizTypeEnum bizType) {
        // 映射表（禁恒等）：1-文章↔1、2-评论↔2、3-用户名↔3 同值；
        // 5(私信)↔4、6(图片)↔5 错位；4-作家申请无映射（返回 null，调用方不反查举报）
        if (bizType == null) {
            return null;
        }
        return switch (bizType) {
            case ARTICLE -> TargetTypeEnum.ARTICLE;
            case COMMENT -> TargetTypeEnum.COMMENT;
            case USERNAME -> TargetTypeEnum.USER;
            case MESSAGE -> TargetTypeEnum.MESSAGE;
            case FILE -> TargetTypeEnum.IMAGE;
            case AUTHOR_APPLY -> null;
        };
    }
}