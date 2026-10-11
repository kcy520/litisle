package com.litisle.litislereview.service.impl;

import com.litisle.litislereview.domain.dto.TaskDecideDTO;
import com.litisle.litislereview.domain.vo.ReviewTaskVO;
import com.litisle.litislereview.service.ReviewTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 人审任务链路服务实现（骨架版：逐方法 TODO 占位）
 */
@Service
@Slf4j
public class ReviewTaskServiceImpl implements ReviewTaskService {

    /** 人审队列：TODO REVIEWER/ADMIN 校验 + layer2_risk 降序 + 认领配额过滤 */
    @Override
    public List<ReviewTaskVO> listQueue(Long operatorId) {
        throw new UnsupportedOperationException("listQueue 待实现");
    }

    /** 任务详情：TODO REVIEWER/ADMIN 校验 + 证据图经 FileClient 批量取 URL */
    @Override
    public ReviewTaskVO getTask(Long taskId, Long operatorId) {
        throw new UnsupportedOperationException("getTask 待实现");
    }

    /** 认领任务：TODO CAS where id=? and status=2 and assignee is null；affected=0 抛 70004 */
    @Override
    public void claimTask(Long taskId, Long reviewerId) {
        throw new UnsupportedOperationException("claimTask 待实现");
    }

    /** 裁决任务：TODO 终态 CAS + REJECT 联动（下架事件/处罚落行/违规分累加/举报回写）+ 高风险 confirm 双签 */
    @Override
    public void decideTask(Long taskId, TaskDecideDTO dto, Long reviewerId) {
        throw new UnsupportedOperationException("decideTask 待实现");
    }
}
