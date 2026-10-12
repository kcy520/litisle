package com.litisle.litislereview.service;

import com.litisle.litislereview.domain.dto.TaskDecideDTO;
import com.litisle.litislereview.domain.vo.ReviewTaskVO;

import java.util.List;

/**
 * 人审任务链路服务（对应实体：review_task）
 *
 * 落地顺序建议（依赖递进）：
 * listQueue → getTask → claimTask（CAS 70004）→ decideTask（终态 CAS + 联动）
 *
 * 【注入计划】实现时注入 ReviewTaskMapper；decideTask 的 REJECT 联动（punish_log 落行、
 * violation_score 累加、report 回写）为同模块跨域写，直接走对应 Mapper（避免服务间循环依赖），
 * 高风险 confirm 双签走 ModerationRecordMapper（mod_type=6）
 */
public interface ReviewTaskService {

    /**
     * 人审队列：REVIEWER/ADMIN；返回待审/在审任务（layer2_risk 降序 + create_time 升序），
     * 支持配额校验（max-active-per-reviewer=20 防单人占满队列）
     */
    List<ReviewTaskVO> listQueue(Long operatorId);

    /**
     * 任务详情：REVIEWER/ADMIN；含机审三分态/命中词快照/证据图（证据经 FileClient 批量取预签名 URL）
     */
    ReviewTaskVO getTask(Long taskId, Long operatorId);

    /**
     * 认领任务：CAS where id=? and status=2 and assignee is null，affected=0 抛 70004（已被他人认领）；
     * 写 assignee + claim_time（sweeper 超时回池基准）
     */
    void claimTask(Long taskId, Long reviewerId);

    /**
     * 裁决任务：终态 CAS（where status=2）置 {3,4} + decided_by/opinion/decide_time，天然拦截重复 decide；
     * REJECT 联动：内容下架事件 + punish_log 落行 + violation_score 原子累加 + 举报回写 status=3；
     * 高风险裁决（人审直接封禁）需 confirm=true + 双签留痕（moderation_record mod_type=6）
     *
     * <p>【板块五 5-2.1 挂点（实现挂起）】处罚落行成功后同事务发 punish.result 事件
     * （组装/发布口径见 MachineRejectPunishGate 类注【5-2.1 发布端收口】，复用同一口径禁另起分支）：
     * REJECT→punish_log(action=3, operator_id=审核员) + 事件(action=3, op=1)；
     * 人审直接封禁→punish_log(action=2) + 事件(action=2, op=1, frozenUntil=null 永久)，
     * auth 消费端据此 kickout 强制下线全会话（5-2.3 封禁即时生效）。</p>
     */
    void decideTask(Long taskId, TaskDecideDTO dto, Long reviewerId);
}
