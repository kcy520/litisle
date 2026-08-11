//package com.litisle.litisleuser.controller;
//
//// 关注/粉丝
////关注/粉丝计数用 Redis String，异步落库。
//@RestController
//@RequestMapping("/v1/user/follow")
//@PreAuthorize("isAuthenticated()")
//public class FollowController {
//
//    private final FollowService followService;
//
//    public FollowController(FollowService followService) {
//        this.followService = followService;
//    }
//
//    /**
//     * 关注用户
//     */
//    @PostMapping("/{targetUserId}")
//    public CommonResult<Void> follow(@PathVariable Long targetUserId) {
//        followService.follow(targetUserId);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 取消关注
//     */
//    @DeleteMapping("/{targetUserId}")
//    public CommonResult<Void> unfollow(@PathVariable Long userId) {
//        followService.unfollow(userId);
//        return CommonResult.ok();
//    }
//
//    /**
//     * 我的关注列表
//     */
//    @GetMapping("/following")
//    public CommonResult<PageVO<UserSimpleVO>> following(@Valid PageQueryDTO query) {
//        return CommonResult.ok(followService.myFollowing(query));
//    }
//
//    /**
//     * 我的粉丝列表
//     */
//    @GetMapping("/followers")
//    public CommonResult<PageVO<UserSimpleVO>> followers(@Valid PageQueryDTO query) {
//        return CommonResult.ok(followService.myFollowers(query));
//    }
//
//    /**
//     * 关注/粉丝计数，可从 Redis 读
//     */
//    @GetMapping("/counts")
//    public CommonResult<FollowCountVO> counts() {
//        return CommonResult.ok(followService.myCounts());
//    }
//}