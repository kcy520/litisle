package com.litisle.litislereview.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.constant.RoleCode;
import com.litisle.litislereview.domain.dto.TaskDecideDTO;
import com.litisle.litislereview.domain.vo.ReviewTaskVO;
import com.litisle.litislereview.service.ReviewTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST 接口层（路径前缀 /api/review，Knife4j 已扫本包）——人审任务分组：
 *
 * | 方法 | 路径                  | 说明                               | 权限           |
 * |------|-----------------------|------------------------------------|----------------|
 * | GET  | /task/queue           | 人审队列                           | REVIEWER/ADMIN |
 * | GET  | /task/{taskId}        | 任务详情（含机审证据）             | REVIEWER/ADMIN |
 * | POST | /task/{taskId}/claim  | 认领（CAS，70004 已被他人认领）    | REVIEWER/ADMIN |
 * | POST | /task/{taskId}/decide | 裁决（终态 CAS + 联动）            | REVIEWER/ADMIN |
 *
 * 要点（原 ReviewController 通用约束，拆分后各组保留同一口径）：
 * - operatorId 统一 StpUtil.getLoginIdAsLong() 取当前登录人（Sa-Token 会话共享口径，与 file 模块一致）
 * - 角色校验（REVIEWER/ADMIN、原裁决人回避、申诉人=owner）在 Service 层统一落地，
 *   Controller 只做 @SaCheckLogin 登录闸门 + 参数校验（@Valid），与 file 模块口径一致
 * - 队列/裁决类接口后续按需加 @RateLimiter 与 FileGlobalQpsInterceptor 同款全站 QPS 拦截器
 * - 安全校验（企业规范，对齐 user 模块 UserAdminController 口径）：类级 @SaCheckRole(REVIEWER/ADMIN OR)
 *   做角色总闸（未登录由 Sa-Token 抛 NotLoginException，无对应角色抛 NotRoleException），
 *   方法级 @SaCheckLogin 保留作为显式登录闸门（冗余但无害，双保险）
 * - 所有返回 Result<T>；错误码：70004-任务已被他人认领 等
 */
@Tag(name = "审核服务-人审任务", description = "人审队列/详情/认领/裁决")
@SaCheckRole(value = {RoleCode.REVIEWER, RoleCode.ADMIN}, mode = SaMode.OR)
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewTaskController {

    private final ReviewTaskService reviewTaskService;

    @Operation(summary = "人审队列", description = "REVIEWER/ADMIN；按小审风险降序 + 时间升序；单人认领上限20")
    @GetMapping("/task/queue")
    @SaCheckLogin
    public Result<List<ReviewTaskVO>> listQueue() {
        return Result.success(reviewTaskService.listQueue(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "任务详情", description = "REVIEWER/ADMIN；含机审三分态/命中词快照/证据图预签名URL")
    @GetMapping("/task/{taskId}")
    @SaCheckLogin
    public Result<ReviewTaskVO> getTask(@PathVariable("taskId") Long taskId) {
        return Result.success(reviewTaskService.getTask(taskId, StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "认领任务", description = "CAS 条件更新；已被他人认领返回 70004")
    @PostMapping("/task/{taskId}/claim")
    @SaCheckLogin
    public Result<Void> claimTask(@PathVariable("taskId") Long taskId) {
        reviewTaskService.claimTask(taskId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "裁决任务", description = "终态 CAS 天然拦截重复裁决；REJECT 联动处罚/下架/举报回写")
    @PostMapping("/task/{taskId}/decide")
    @SaCheckLogin
    public Result<Void> decideTask(@PathVariable("taskId") Long taskId,
                                   @Valid @RequestBody TaskDecideDTO dto) {
        reviewTaskService.decideTask(taskId, dto, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}
