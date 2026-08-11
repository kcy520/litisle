//package com.litisle.litisleuser.controller;
//
////// 注销账号
///// //注销账号有逻辑注销、二次确认、30 天冷却期。
//@RestController
//@RequestMapping("user/account")
//@PreAuthorize("isAuthenticated()")
//public class AccountCancelController {
//
//    private final AccountCancelService accountCancelService;
//
//    public AccountCancelController(AccountCancelService accountCancelService) {
//        this.accountCancelService = accountCancelService;
//    }
//
//    /**
//     * 发起注销申请，返回是否需要二次确认、冷却信息等
//     */
//    @PostMapping("/cancel/request")
//    public CommonResult<AccountCancelRequestVO> requestCancel() {
//        return CommonResult.ok(accountCancelService.requestCancel());
//    }
//
//    /**
//     * 二次确认注销
//     */
//    @PostMapping("/cancel/confirm")
//    public CommonResult<Void> confirmCancel(@Valid @RequestBody AccountCancelConfirmDTO dto) {
//        accountCancelService.confirmCancel(dto);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 查询注销状态/冷却期
//     */
//    @GetMapping("/cancel/status")
//    public CommonResult<AccountCancelStatusVO> cancelStatus() {
//        return CommonResult.ok(accountCancelService.status());
//    }
//}