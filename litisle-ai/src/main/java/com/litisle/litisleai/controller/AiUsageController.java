package com.litisle.litisleai.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.constant.RoleCode;
import com.litisle.litisleai.dto.AiUsageDetailItem;
import com.litisle.litisleai.dto.AiUsageMeResponse;
import com.litisle.litisleai.dto.AiUsageSummaryResponse;
import com.litisle.litisleai.service.AiUsageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * AI 用量查询 REST 接入层（板块六 6.1 / F9 收口：个人用量查询 + 运营侧按用途/按日出账）
 *
 * <p>【职责边界】controller 只做协议转换、登录态/角色校验与统一响应包装；数据归属过滤、
 * 窗口与分页收口、聚合组装全部收口在 AiUsageQueryService。</p>
 *
 * <p>【身份口径】userId 属于登录态数据，禁止客户端传入：用户侧端点统一以 Sa-Token 当前登录用户
 * （StpUtil.getLoginIdAsLong）作为归属依据，跨用户按不存在处理；匿名调用不进用户侧查询，
 * 只进运营汇总（口径见 AiUsageQueryService）。运营侧端点要求 ADMIN 角色。</p>
 *
 * <p>【网关口径】/ai/** 业务前缀按业务指南由网关以 /api/ai/** 暴露（落地前须在
 * Api-Architecture 定稿登记，随板块七 7.1 一并登记）。</p>
 *
 * @author litisle
 */
@RestController
@RequestMapping("/ai/usage")
@RequiredArgsConstructor
public class AiUsageController {

    /** 用量查询服务（数据归属过滤与聚合组装收口） */
    private final AiUsageQueryService usageQueryService;

    /**
     * 个人用量查询（GET /ai/usage/me，F9 收口：剩余配额、当日用量、花费统计）
     *
     * @return 当日各用途用量与剩余配额 + 当日总花费 + 近 7 日花费统计
     */
    @GetMapping("/me")
    public Result<AiUsageMeResponse> me() {
        return Result.success(usageQueryService.usageMe(currentUserId()));
    }

    /**
     * 个人用量明细分页查询（GET /ai/usage/me/details；窗口默认当日、最多近 7 天防深翻大查询）
     *
     * @param days     查询窗口天数（1~7，默认 1=当日）
     * @param pageNo   页码（从 1 起）
     * @param pageSize 页大小（默认 20，上限 100）
     * @return 当页明细行（按调用完成时间倒序）
     */
    @GetMapping("/me/details")
    public Result<List<AiUsageDetailItem>> myDetails(@RequestParam(value = "days", required = false) Integer days,
                                                     @RequestParam(value = "pageNo", required = false) Integer pageNo,
                                                     @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return Result.success(usageQueryService.usageDetails(currentUserId(), days, pageNo, pageSize));
    }

    /**
     * 运营侧用量汇总查询（GET /ai/usage/summary，F9 验收「后台可按场景出账，单日花费可对账」；
     * 跨用户求和，含匿名/机审系统桶用量）
     *
     * @param start 统计日期起点（含，默认近 7 天起点；区间上限 31 天防聚合大查询）
     * @param end   统计日期终点（含，默认当日）
     * @return 按用途汇总 + 按日汇总
     */
    @GetMapping("/summary")
    public Result<AiUsageSummaryResponse> summary(
            @RequestParam(value = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(value = "end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        // 运营侧端点：ADMIN 角色校验（未登录/无角色异常由 SaTokenExceptionHandler 转统一错误）
        StpUtil.checkRole(RoleCode.ADMIN);
        return Result.success(usageQueryService.summary(start, end));
    }

    /**
     * 取当前登录用户 ID（未登录时抛出未登录异常，由 SaTokenExceptionHandler 转 10002 标准错误）
     *
     * @return 当前登录用户 ID
     */
    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}
