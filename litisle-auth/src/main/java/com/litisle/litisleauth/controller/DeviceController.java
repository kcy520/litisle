//package com.litisle.litisleauth.controller;
//
//
//// 在线设备管理
//@RestController
//@RequestMapping("/v1/auth/devices")
//@PreAuthorize("isAuthenticated()")
//public class DeviceController {
//
//    private final DeviceSessionService deviceSessionService;
//
//    public DeviceController(DeviceSessionService deviceSessionService) {
//        this.deviceSessionService = deviceSessionService;
//    }
//
//    /**
//     * 当前账号在线设备列表
//     */
//    @GetMapping
//    public CommonResult<List<DeviceSessionVO>> list() {
//        return CommonResult.ok(deviceSessionService.listCurrentUserDevices());
//    }
//
//    /**
//     * 踢出指定设备
//     */
//    @DeleteMapping("/{deviceId}")
//    public CommonResult<Void> kick(@PathVariable String deviceId) {
//        deviceSessionService.kickDevice(deviceId);
//        return CommonResult.ok();
//    }
//}