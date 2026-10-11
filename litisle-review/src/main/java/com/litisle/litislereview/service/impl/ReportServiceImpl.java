package com.litisle.litislereview.service.impl;

import com.litisle.litislereview.domain.dto.ReportSubmitDTO;
import com.litisle.litislereview.domain.entity.Report;
import com.litisle.litislereview.domain.vo.ReportVO;
import com.litisle.litislereview.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 举报链路服务实现（骨架版：逐方法 TODO 占位）
 *
 * 【注意】机审执行（DFA/LLM）不在本类，放 listener + ModerationService；
 * 本类仅负责举报登记与初审分流编排
 */
@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    /** 提交举报：TODO Step1 防自举报 → Step2 活跃去重 → Step3 落 report+证据 → Step4 DFA 初审分流 */
    @Override
    public ReportVO submitReport(ReportSubmitDTO dto, Long reporterId) {
        throw new UnsupportedOperationException("submitReport 待实现");
    }

    /** 撤回举报：TODO CAS where reporter_id=? and status<3 置 4-已撤回；affected=0 抛错 */
    @Override
    public void withdrawReport(Long reportId, Long reporterId) {
        throw new UnsupportedOperationException("withdrawReport 待实现");
    }

    /** 内部：按举报ID查原始行（未来 ReviewClient Provider 委托，网关不路由） */
    @Override
    public Report getInternalReport(Long reportId) {
        throw new UnsupportedOperationException("getInternalReport 待实现");
    }
}
