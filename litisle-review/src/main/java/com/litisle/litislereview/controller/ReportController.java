package com.litisle.litislereview.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.litislereview.domain.dto.ReportSubmitDTO;
import com.litisle.litislereview.domain.vo.ReportVO;
import com.litisle.litislereview.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * REST 接口层（路径前缀 /api/review，Knife4j 已扫本包）——举报分组：
 *
 * | 方法   | 路径                | 说明                                  | 权限 |
 * |--------|--------------------|---------------------------------------|------|
 * | POST   | /report            | 提交举报（防自举报+去重+证据登记）      | 登录 |
 * | DELETE | /report/{reportId} | 撤回举报（仅本人，status<3）           | 登录 |
 * | GET    | /report/my         | 我的举报列表（TODO 待实现）            | 登录 |
 *
 * 要点（原 ReviewController 通用约束，拆分后各组保留同一口径）：
 * - operatorId 统一 StpUtil.getLoginIdAsLong() 取当前登录人（Sa-Token 会话共享口径，与 file 模块一致）
 * - 角色校验（REVIEWER/ADMIN、原裁决人回避、申诉人=owner）在 Service 层统一落地，
 *   Controller 只做 @SaCheckLogin 登录闸门 + 参数校验（@Valid），与 file 模块口径一致
 * - 所有返回 Result<T>；错误码：70004-任务已被他人认领 等
 */
@Tag(name = "审核服务-举报-未实现", description = "举报提交/撤回")
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @Operation(summary = "提交举报", description = "防自导自演（reporter≠目标owner）+ 活跃举报去重；证据图最多9张")
    @PostMapping("/report")
    @SaCheckLogin
    public Result<ReportVO> submitReport(@Valid @RequestBody ReportSubmitDTO dto) {
        ReportVO reportVO = reportService.submitReport(dto, StpUtil.getLoginIdAsLong());
        return Result.success(reportVO);
    }

    @Operation(summary = "撤回举报", description = "仅举报人本人、仅 status<3 可撤回；已建审核任务不撤销")
    @DeleteMapping("/report/{reportId}")
    @SaCheckLogin
    public Result<Void> withdrawReport(@PathVariable("reportId") Long reportId) {
        reportService.withdrawReport(reportId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}
