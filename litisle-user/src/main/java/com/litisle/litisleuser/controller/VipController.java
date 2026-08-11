//package com.litisle.litisleuser.controller;
//
//
//// VIP 状态
//提醒逻辑一般不在 Controller 触发，而是由定时任务/JOB 扫描，然后发站内信。
////VIP 有到期计算、到期前 7 天提醒、到期后冻结权益。
//@RestController
//@RequestMapping("/v1/user/vip")
//public class VipController {
//
//    private final VipService vipService;
//
//    public VipController(VipService vipService) {
//        this.vipService = vipService;
//    }
//
//    /**
//     * 当前用户 VIP 状态
//     */
//    @GetMapping("/me")
//    @PreAuthorize("isAuthenticated()")
//    public CommonResult<VipStatusVO> myVipStatus() {
//        return CommonResult.ok(vipService.currentVipStatus());
//    }
//
//    /**
//     * 购买/开通 VIP
//     * 如果走订单服务，这里可能只是接收订单回调或提供状态查询
//     */
//    @PostMapping("/activate")
//    @PreAuthorize("isAuthenticated()")
//    public CommonResult<Void> activate(@Valid @RequestBody VipActivateDTO dto) {
//        vipService.activate(dto);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 后台手动调整 VIP，仅供管理员
//     */
//    @PatchMapping("/users/{userId}")
//    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
//    public CommonResult<Void> adminAdjust(
//            @PathVariable Long userId,
//            @Valid @RequestBody VipAdjustDTO dto
//    ) {
//        vipService.adminAdjust(userId, dto);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 定时任务/内部接口：扫描即将到期、已到期用户
//     * 不建议对外暴露，可以放内网或加内部 token
//     */
//    @PostMapping("/internal/expire-scan")
//    @PreAuthorize("hasRole('INTERNAL_JOB') or @authz.isInternalRequest()")
//    public CommonResult<Void> expireScan() {
//        vipService.scanAndProcessExpiredVip();
//        return CommonResult.ok();
//    }
//}