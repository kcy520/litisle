package com.litisle.litislefile.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonsecurity.constant.RoleCode;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislefile.service.FileManageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;
import static com.litisle.litislefile.constant.FileLimitConstant.*;

/**
 * REST 接口层（路径前缀 /api/file，Knife4j 已扫本包）——文件管理域（由 FileController 按业务域拆分）：
 *
 * | 方法   | 路径                 | 说明                                        | 权限            |
 * |--------|---------------------|---------------------------------------------|-----------------|
 * | DELETE | /{fileId}           | 解绑引用；无活跃引用才 del_flag=1（方案C，7 天后物理清理） | owner/ADMIN |
 * | GET    | /test               | 限流测试接口（非业务端点）                    | ADMIN（测试端点不对外暴露） |
 *
 * 要点：
 * - ownerId 统一从网关注入的 X-User-Id Header 取（HeaderConstant），不信任请求参数
 *   （实施更正 2026-09-22：网关 Header 注入尚未落地，全项目现行口径为 Sa-Token 会话共享，
 *   统一 StpUtil.getLoginIdAsLong() 取当前登录人——与 auth/user 一致；未来接入 Header 方案时再切换）
 * - 引用登记入口（P0 已落地）：跨服务业务方经 FileClient（Feign，/api/feign/file/ref/bind|unbind，
 *   FileFeignProviderController 提供）与业务表同事务登记；路径前缀网关未路由，仅服务间可达
 *   （file_ref 引用登记，方案C——秒传共享防悬空，见 Sql-Document §八 / seaweedfs笔记.md §3.4）
 * - 所有返回 Result<T>；错误码见 File-Architecture.md §六
 * - presign/DELETE 需校验 owner 或 ADMIN，否则 BizException(NO_PERMISSION)
 */
@Tag(name = "文件服务-管理", description = "SeaweedFS 方案C 解绑/删除")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileManageController {

    private final FileManageService fileService;

    /**
     * 删除（方案C 解绑语义）：解绑调用方引用；无活跃引用才 del_flag=1，7 天后物理清理
     */
    @Operation(summary = "删除文件", description = "owner/ADMIN；方案C 三步解绑：删本人引用→他人仍引用则保留→无引用才软删。"
            + "注意：ADMIN 代删只解除 ADMIN 本人引用行，真 owner/他人的引用不受影响（防悬空设计），"
            + "被引用文件 ADMIN 也无法删除，仅真 owner 可完成解绑+软删全流程")
    @RateLimiter(time = DELETE_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = DELETE_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @DeleteMapping("/{fileId}")
    @SaCheckLogin
    public Result<Void> delete(@PathVariable("fileId") String fileId) {
        // 方案C 三步解绑语义，详见上方 @Operation 描述
        fileService.delete(fileId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }

    /**
     * 测试接口
     */
    @Operation(summary = "限流测试", description = "测试reids限流")
    @RateLimiter(time = DELETE_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = 2, keyType = RateLimiter.KeyType.USER)
    @GetMapping("/test")
    @SaCheckRole(RoleCode.ADMIN)
    public Result<String> test() {
        System.out.println("接口测试");
        return Result.success("测试成功!");
    }
}
