package com.litisle.litislereview.service;

import com.litisle.litislereview.domain.dto.AppealDecideDTO;
import com.litisle.litislereview.domain.dto.AppealSubmitDTO;

/**
 * 申诉链路服务（对应实体：appeal）
 *
 * 落地顺序建议：submitAppeal（归属校验 + 双层防重）→ judgeAppeal（ADMIN 回避 + confirm 双签）
 *
 * 【注入计划】实现时注入 AppealMapper；归属回查需 ReviewTaskMapper/PunishLogMapper（按 biz_type 解析
 * owner），judgeAppeal 改判联动走 PunishLogMapper（撤销处罚）+ ViolationScoreMapper（违规分重算），
 * 双签留痕走 ModerationRecordMapper
 */
public interface AppealService {

    /**
     * 提交申诉：appellant 与内容 owner 一致校验（biz_type→report.target_type 映射回查归属）→
     * 双层防重（关联列级 + 内容级 status=0）→ 落 appeal（biz_type/biz_id 由关联列解析固化）
     */
    void submitAppeal(AppealSubmitDTO dto, Long appellantId);

    /**
     * 裁决申诉：仅 ADMIN（REVIEWER 不可自裁）+ 原驳回裁决人回避 + 改判撤销高风险需 confirm=true 双签；
     * 维持 → 置 status=1；撤销 → 置 status=2 + punish_log 撤销/violation_score 重算/
     * 同内容活跃举报批量回写 status=3（按 target_type+target_id，非仅 appeal.report_id）
     */
    void judgeAppeal(Long appealId, AppealDecideDTO dto, Long judgeId);
}
