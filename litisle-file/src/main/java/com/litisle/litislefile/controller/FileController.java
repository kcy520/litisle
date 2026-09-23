package com.litisle.litislefile.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislefile.domain.vo.FileMetaVO;
import com.litisle.litislefile.domain.vo.FileUploadVO;
import com.litisle.litislefile.domain.vo.PresignVO;
import com.litisle.litislefile.domain.vo.ThumbnailVO;
import com.litisle.litislefile.enums.BizTypeEnum;
import com.litisle.litislefile.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.TimeUnit;
import static com.litisle.litislefile.constant.FileLimitConstant.*;

/**
 * REST 接口层（路径前缀 /api/file，Knife4j 已扫本包）：
 *
 * | 方法   | 路径                 | 说明                                        | 权限            |
 * |--------|---------------------|---------------------------------------------|-----------------|
 * | POST   | /upload             | 单文件上传（multipart: file + bizType）      | 登录            |
 * | POST   | /upload/batch       | 批量上传（正文多图，上限 9 张）               | 登录            |
 * | GET    | /{fileId}/url       | 取 URL：PUBLIC→直链；PRIVATE→预签名(30min)   | 登录(PUBLIC)；PRIVATE 需 owner/ADMIN |
 * | POST   | /{fileId}/presign   | 强制预签名（expireMinutes ≤ 配置上限，二次校验） | owner/ADMIN     |
 * | GET    | /{fileId}/meta      | 元数据（mime/size/宽高/align）                | 登录            |
 * | POST   | /{fileId}/thumbnail | 缩略图 URL（w/h 可选，默认 yml 配置）         | 登录            |
 * | DELETE | /{fileId}           | 解绑引用；无活跃引用才 del_flag=1（方案C，7 天后物理清理） | owner/ADMIN |
 *
 * 要点：
 * - ownerId 统一从网关注入的 X-User-Id Header 取（HeaderConstant），不信任请求参数
 *   （实施更正 2026-09-22：网关 Header 注入尚未落地，全项目现行口径为 Sa-Token 会话共享，
 *   统一 StpUtil.getLoginIdAsLong() 取当前登录人——与 auth/user 一致；未来接入 Header 方案时再切换）
 * - 权限口径统一：PRIVATE 文件取 URL 与 presign 一致，一律 owner/ADMIN 校验——
 *   预签名 URL 本质是可传播凭证，入口必须收紧（否则任意登录用户可拿 URL 转发，见审查表 #6）
 * - expireMinutes 必须校验 ≤ 配置上限（presign-expire-minutes），防传 99999 绕过 LICENSE 限时
 * - MIME 白名单按 bizType 区分：图片类限 image/jpeg|png|webp|gif；LICENSE 放开授权文件类型
 * - 引用登记无 REST 入口：业务方经编译期依赖 FileService.bindRef/unbindRef 与业务表同事务登记
 *   （file_ref 引用登记，方案C——秒传共享防悬空，见 Sql-Document §八 / seaweedfs笔记.md §3.4）
 * - 所有返回 Result<T>；错误码见 File-Architecture.md §六
 * - presign/DELETE 需校验 owner 或 ADMIN，否则 BizException(NO_PERMISSION)
 */
@Tag(name = "文件服务", description = "SeaweedFS 文件上传/访问/方案C 解绑")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    /**
     * 单文件上传（含秒传：同文件二次上传 deduplicated=true 不落 SeaweedFS）
     */
    @Operation(summary = "单文件上传", description = "multipart: file + bizType；size≤10MB，MIME 白名单按 bizType 区分")
    @RateLimiter(time = UPLOAD_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = UPLOAD_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @PostMapping("/upload")
    @SaCheckLogin
    public Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam("bizType") BizTypeEnum bizType) {
        return Result.success(fileService.upload(file, bizType, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 批量上传（正文多图，上限 9 张，逐个走单文件链路）
     */
    @Operation(summary = "批量上传", description = "上限 9 张；逐个独立上传，中途失败抛异常时已成功的文件不回滚，前端/调用方需自行处理部分成功场景")
    @RateLimiter(time = UPLOAD_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = BATCH_UPLOAD_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @PostMapping("/upload/batch")
    @SaCheckLogin
    public Result<List<FileUploadVO>> uploadBatch(@RequestParam("files") List<MultipartFile> files,
                                                  @RequestParam("bizType") BizTypeEnum bizType) {
        return Result.success(fileService.uploadBatch(files, bizType, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 取 URL：PUBLIC→直链；PRIVATE→预签名（30min，owner/ADMIN 校验）
     */
    @Operation(summary = "获取访问 URL", description = "PUBLIC 登录即可；PRIVATE 需 owner/ADMIN，口径与 presign 统一（审查表 #6）")
    @GetMapping("/{fileId}/url")
    @SaCheckLogin
    public Result<String> getUrl(@PathVariable("fileId") String fileId) {
        return Result.success(fileService.getUrl(fileId, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 强制预签名（owner/ADMIN；expireMinutes ≤ 配置上限，防 99999 绕过 LICENSE 限时）
     */
    @Operation(summary = "强制预签名", description = "仅 PRIVATE 文件；expireMinutes 空取配置默认，上限同配置")
    @PostMapping("/{fileId}/presign")
    @SaCheckLogin
    public Result<PresignVO> presign(@PathVariable("fileId") String fileId,
                                     @RequestParam(value = "expireMinutes", required = false) Integer expireMinutes) {
        return Result.success(fileService.presign(fileId, expireMinutes, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 元数据查询（mime/size/宽高/align）
     */
    @Operation(summary = "文件元数据", description = "登录即可查询；非 owner/ADMIN 脱敏 ownerId；文件不存在抛 FILE_NOT_FOUND")
    @GetMapping("/{fileId}/meta")
    @SaCheckLogin
    public Result<FileMetaVO> getMeta(@PathVariable("fileId") String fileId) {
        return Result.success(fileService.getMeta(fileId, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 缩略图 URL（w/h 可选，默认 yml 配置；按需生成，不落库）
     */
    @Operation(summary = "获取缩略图 URL", description = "w/h 可选（默认 300×300，上限 2000）；非图片拒绝")
    @PostMapping("/{fileId}/thumbnail")
    @SaCheckLogin
    public Result<ThumbnailVO> thumbnail(@PathVariable("fileId") String fileId,
                                         @RequestParam(value = "w", required = false) Integer width,
                                         @RequestParam(value = "h", required = false) Integer height) {
        return Result.success(fileService.getThumbnail(fileId, width, height));
    }

    /**
     * 删除（方案C 解绑语义）：解绑调用方引用；无活跃引用才 del_flag=1，7 天后物理清理
     */
    @Operation(summary = "删除文件", description = "owner/ADMIN；方案C 三步解绑：删本人引用→他人仍引用则保留→无引用才软删。"
            + "注意：ADMIN 代删只解除 ADMIN 本人引用行，真 owner/他人的引用不受影响（防悬空设计），"
            + "被引用文件 ADMIN 也无法删除，仅真 owner 可完成解绑+软删全流程")
    @DeleteMapping("/{fileId}")
    @SaCheckLogin
    public Result<Void> delete(@PathVariable("fileId") String fileId) {
        fileService.delete(fileId, StpUtil.getLoginIdAsLong());
        return Result.success();
    }
}
