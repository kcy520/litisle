package com.litisle.litisleuser.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleuser.domain.vo.UserPublicVO;
import com.litisle.litisleuser.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户间搜索接口
 * <p>
 * 【使用场景】通过对外 UID 精确搜索用户公开资料，返回不带内部 userId 的 UserPublicVO，
 * 走 uk_uid 唯一索引，避免暴露数据库自增主键、防止被遍历爬取。
 */
@Tag(name = "用户搜索接口", description = "按 UID 搜索用户公开资料")
@RestController
@RequestMapping("/api/user/search")
@SaCheckLogin
public class UserSearchController {

    private final UserProfileService userProfileService;

    public UserSearchController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /**
     * 按 UID 精确搜索用户公开资料
     *
     * @param uid 对外 UID
     * @return 目标用户公开资料
     */
    @Operation(summary = "按 UID 搜索用户公开资料")
    @GetMapping("/{uid}")
    public Result<UserPublicVO> searchByUid(@PathVariable String uid) {
        UserPublicVO userPublicVO = userProfileService.searchByUid(uid);
        return Result.success(userPublicVO);
    }
}