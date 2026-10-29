package com.litisle.litislecontent.controller;


import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislecontent.domain.dto.FavoriteDTO;
import com.litisle.litislecontent.domain.dto.FavoriteQueryDTO;
import com.litisle.litislecontent.domain.vo.FavoriteItemVO;
import com.litisle.litislecontent.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

import static com.litisle.litislecontent.constant.ContentLimitConstant.*;

/**
 * REST 接口层——收藏域（阶段16：一期 content 临时承载单层收藏，social 收藏夹立项后迁移）：
 *
 * | 方法   | 路径      | 说明                                       | 权限     |
 * |--------|-----------|--------------------------------------------|----------|
 * | POST   | /favorite | 收藏（幂等，重复收藏视为成功）             | 登录·限频 |
 * | DELETE | /favorite | 取消收藏（幂等，未收藏也成功）             | 登录·限频 |
 * | GET    | /favorite | 我的收藏列表（文章/书籍/全部 tab）         | 登录     |
 * | GET    | /favorite/check | 收藏状态查询                         | 登录     |
 *
 * 越权口径：收藏/取消/列表/状态均以当前登录人 userId 为准（StpUtil 取），不接受前端传 userId。
 * 目标可见性校验在 Service 层（不允许收藏自己看不到的内容）。
 */
@Tag(name = "内容服务-收藏", description = "收藏/取消收藏/我的收藏/收藏状态")
@RestController
@RequestMapping("/api/content/favorite")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * 收藏目标（幂等；重复收藏捕获唯一键冲突按成功处理）
     */
    @Operation(summary = "收藏", description = "登录；限频 20/60s；幂等")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = FAVORITE_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @PostMapping
    public Result<Void> favorite(@RequestBody @Valid FavoriteDTO dto) {
        // Service 内类型白名单 + 目标可见性校验 + 幂等插入
        favoriteService.favorite(StpUtil.getLoginIdAsLong(), dto.getTargetType(), dto.getTargetId());
        return Result.success();
    }

    /**
     * 取消收藏（幂等；未收藏也视为成功）
     */
    @Operation(summary = "取消收藏", description = "登录；限频 20/60s；幂等")
    @RateLimiter(time = LIMIT_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = FAVORITE_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @SaCheckLogin
    @DeleteMapping
    public Result<Void> unfavorite(@RequestParam("targetType") Integer targetType,
                                   @RequestParam("targetId") Long targetId) {
        // Service 内类型白名单校验 + 条件删除
        favoriteService.unfavorite(StpUtil.getLoginIdAsLong(), targetType, targetId);
        return Result.success();
    }

    /**
     * 我的收藏列表（create_time 倒序；对象信息已装配；空收藏返回空页）
     */
    @Operation(summary = "我的收藏", description = "登录；targetType null-全部 1-文章 2-书籍")
    @SaCheckLogin
    @GetMapping
    public Result<Page<FavoriteItemVO>> myFavorites(FavoriteQueryDTO query) {
        // targetType 白名单校验在 Service 层（非法值 PARAM_ERROR）
        return Result.success(favoriteService.pageMyFavorites(
                StpUtil.getLoginIdAsLong(), query.getTargetType(),
                query.getPageNo(), query.getPageSize()));
    }

    /**
     * 收藏状态查询（前端详情页/列表按钮态用；详情 VO 的 favorited 由 getDetail 直接回填）
     */
    @Operation(summary = "收藏状态", description = "登录；返回当前用户是否已收藏该目标")
    @SaCheckLogin
    @GetMapping("/check")
    public Result<Boolean> check(@RequestParam("targetType") Integer targetType,
                                 @RequestParam("targetId") Long targetId) {
        // 等值查询委托 FavoriteStateSupport 单点收口
        return Result.success(favoriteService.isFavorited(
                StpUtil.getLoginIdAsLong(), targetType, targetId));
    }
}
