package com.litisle.litislereview.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.constant.RoleCode;
import com.litisle.litislereview.domain.dto.AppealDecideDTO;
import com.litisle.litislereview.domain.dto.AppealSubmitDTO;
import com.litisle.litislereview.service.AppealService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * REST 接口层（路径前缀 /api/review，Knife4j 已扫本包）——申诉分组：
 *
 * | 方法 | 路径                      | 说明                                  | 权限  |
 * |------|---------------------------|---------------------------------------|-------|
 * | POST | /appeal                   | 提交申诉（appellant 须为内容 owner）   | 登录  |
 * | POST | /appeal/{appealId}/judge  | 裁决申诉（ADMIN+回避+confirm 双签）    | ADMIN |
 *
 * 要点（原 ReviewController 通用约束，拆分后各组保留同一口径）：
 * - operatorId 统一 StpUtil.getLoginIdAsLong() 取当前登录人（Sa-Token 会话共享口径，与 file 模块一致）
 * - 角色校验（ADMIN、原裁决人回避、申诉人=owner）在 Service 层统一落地，
 *   Controller 只做 @SaCheckLogin 登录闸门 + 参数校验（@Valid），与 file 模块口径一致
 * - 所有返回 Result<T>；错误码：70004-任务已被他人认领 等
 */
@Tag(name = "审核服务-申诉", description = "申诉提交/裁决")
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class AppealController {

    private final AppealService appealService;

    @Operation(summary = "提交申诉", description = "appellant 须与内容 owner 一致；同内容存在待裁决申诉即拒绝（双层防重）")
    @PostMapping("/appeal")
    @SaCheckLogin
    public Result<Void> submitAppeal(@Valid @RequestBody AppealSubmitDTO dto) {
        appealService.submitAppeal(dto, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    @Operation(summary = "裁决申诉", description = "仅 ADMIN；原驳回裁决人回避；撤销封禁/清分需 confirm=true 双签留痕")
    @PostMapping("/appeal/{appealId}/judge")
    @SaCheckLogin
    @SaCheckRole(RoleCode.ADMIN)
    public Result<Void> judgeAppeal(@PathVariable("appealId") Long appealId,
                                    @Valid @RequestBody AppealDecideDTO dto) {
        appealService.judgeAppeal(appealId, dto, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}
