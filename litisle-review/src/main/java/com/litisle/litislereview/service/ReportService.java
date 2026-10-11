package com.litisle.litislereview.service;

import com.litisle.litislereview.domain.dto.ReportSubmitDTO;
import com.litisle.litislereview.domain.entity.Report;
import com.litisle.litislereview.domain.vo.ReportVO;

/**
 * 举报链路服务（对应实体：report + report_evidence）
 *
 * 落地顺序建议（依赖递进）：
 * submitReport（防自举报 + 去重 + 证据登记 + DFA 初审分流）→ withdrawReport（ReportMapper CAS）
 *
 * 【注入计划】实现时需注入 ReportMapper、ReportEvidenceMapper；
 * DFA 初审分流复用 DFA 门面（命中 HIGH 建 review_task 转 ReviewTaskService 落库）
 */
public interface ReportService {

    /**
     * 提交举报：校验 reporter≠目标 owner → 活跃举报去重（active_key 兜底）→ 落 report + 证据关联 →
     * 同步 DFA 初审分流（HIGH 命中建 review_task 转人工 / 低风险机审直处理）
     */
    ReportVO submitReport(ReportSubmitDTO dto, Long reporterId);

    /**
     * 撤回举报：仅举报人本人、仅 status<3（CAS where reporter_id=? and status<3 置 4-已撤回）；
     * 已建 review_task 不撤销
     */
    void withdrawReport(Long reportId, Long reporterId);

    /**
     * 内部：按举报ID查原始行（未来 ReviewClient 契约提供方；网关不路由 /api/feign/**）
     */
    Report getInternalReport(Long reportId);
}
