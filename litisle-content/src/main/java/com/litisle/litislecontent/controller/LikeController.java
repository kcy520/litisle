package com.litisle.litislecontent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.litislecontent.domain.dto.LikeDTO;
import com.litisle.litislecontent.service.LikeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * REST 接口层——点赞域（批次十一 11.8：一期 content 临时承载点赞，social 模块立项后迁移）。
 *
 * | 方法   | 路径   | 说明                                        | 权限 |
 * |--------|--------|---------------------------------------------|------|
 * | POST   | /like  | 点赞（幂等，重复点赞视为成功）              | 登录 |
 * | DELETE | /like  | 取消点赞（幂等，未点赞也成功；物理删除记录）| 登录 |
 *
 * 越权口径：点赞/取消均以当前登录人 userId 为准（StpUtil 取），不接受前端传 userId。
 * 未登录由 @SaCheckLogin 统一拦截为未授权码；目标可见性、作者自赞、频控均在 Service 层处理。
 *
 * @author litisle
 */
@Tag(name = "内容服务-点赞", description = "点赞/取消点赞")
@RestController
@RequestMapping("/api/content/like")
@RequiredArgsConstructor
public class LikeController {

    /** 点赞域服务（幂等写入、可见性校验、计数同步、提交后事件） */
    private final LikeService likeService;

    /**
     * 点赞目标（幂等；重复点赞捕获唯一键冲突按成功处理且不重复加计数）。
     */
    @Operation(summary = "点赞", description = "登录；单用户频控（阈值配置化）；幂等；作者不可自赞")
    @SaCheckLogin
    @PostMapping
    public Result<Void> like(@RequestBody @Valid LikeDTO dto) {
        // userId 取登录态，Service 内完成可见性/自赞/频控校验与幂等插入
        likeService.like(StpUtil.getLoginIdAsLong(), dto.getTargetType(), dto.getTargetId());
        return Result.success();
    }

    /**
     * 取消点赞（幂等；未点赞也视为成功；物理删除点赞记录）。
     */
    @Operation(summary = "取消点赞", description = "登录；单用户频控（阈值配置化）；幂等；物理删除")
    @SaCheckLogin
    @DeleteMapping
    public Result<Void> unlike(@RequestParam("targetType") Integer targetType,
                               @RequestParam("targetId") Long targetId) {
        // Service 内类型白名单校验 + 条件删除（影响行数 > 0 才递减计数）
        likeService.unlike(StpUtil.getLoginIdAsLong(), targetType, targetId);
        return Result.success();
    }
}
