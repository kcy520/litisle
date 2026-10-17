package com.litisle.litislereview.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.litislereview.domain.vo.PunishLogVO;
import com.litisle.litislereview.service.PunishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST 接口层（路径前缀 /api/review，Knife4j 已扫本包）——处罚分组：
 *
 * | 方法 | 路径        | 说明           | 权限 |
 * |------|------------|----------------|------|
 * | GET  | /punish/my | 我的处罚流水   | 登录 |
 *
 * 要点（原 ReviewController 通用约束，拆分后各组保留同一口径）：
 * - operatorId 统一 StpUtil.getLoginIdAsLong() 取当前登录人（Sa-Token 会话共享口径，与 file 模块一致）
 * - 所有返回 Result<T>；错误码：70004-任务已被他人认领 等
 */
@Tag(name = "审核服务-处罚-未实现", description = "处罚流水查询")
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class PunishController {

    private final PunishService punishService;

    @Operation(summary = "我的处罚流水", description = "仅本人；生效+已撤销全量展示")
    @GetMapping("/punish/my")
    @SaCheckLogin
    public Result<List<PunishLogVO>> listMyPunish() {
        List<PunishLogVO> punishLogVOS = punishService.listMyPunish(StpUtil.getLoginIdAsLong());
        return Result.success(punishLogVOS);
    }
}
