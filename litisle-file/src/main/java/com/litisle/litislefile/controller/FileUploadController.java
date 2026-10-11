package com.litisle.litislefile.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislefile.domain.vo.FileUploadVO;
import com.litisle.litislefile.enums.StorageBizTypeEnum;
import com.litisle.litislefile.service.FileUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.TimeUnit;
import static com.litisle.litislefile.constant.FileLimitConstant.*;

/**
 * REST 接口层（路径前缀 /api/file，Knife4j 已扫本包）——文件上传域（由 FileController 按业务域拆分）：
 *
 * | 方法   | 路径                 | 说明                                        | 权限            |
 * |--------|---------------------|---------------------------------------------|-----------------|
 * | POST   | /upload             | 单文件上传（multipart: file + bizType）      | 登录            |
 * | POST   | /upload/batch       | 批量上传（正文多图，上限 9 张）               | 登录            |
 *
 * 要点：
 * - ownerId 统一从网关注入的 X-User-Id Header 取（HeaderConstant），不信任请求参数
 *   （实施更正 2026-09-22：网关 Header 注入尚未落地，全项目现行口径为 Sa-Token 会话共享，
 *   统一 StpUtil.getLoginIdAsLong() 取当前登录人——与 auth/user 一致；未来接入 Header 方案时再切换）
 * - MIME 白名单按 bizType 区分：图片类限 image/jpeg|png|webp|gif；LICENSE 放开授权文件类型
 * - 所有返回 Result<T>；错误码见 File-Architecture.md §六
 */
@Tag(name = "文件服务-上传", description = "SeaweedFS 文件上传")
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileUploadService fileService;

    /**
     * 单文件上传（含秒传：同文件二次上传 deduplicated=true 不落 SeaweedFS）
     */
    @Operation(summary = "单文件上传", description = "multipart: file + bizType；size≤10MB，MIME 白名单按 bizType 区分")
    @RateLimiter(time = UPLOAD_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = UPLOAD_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @PostMapping("/upload")
    @SaCheckLogin
    public Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam("bizType") StorageBizTypeEnum bizType) {
        // StpUtil 取当前登录人作为 ownerId（Sa-Token 会话共享口径），委托 Service 走秒传判重上传链路
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
                                                  @RequestParam("bizType") StorageBizTypeEnum bizType) {
        // 逐个委托单文件链路；中途失败已成功的不回滚，部分成功由调用方处理
        return Result.success(fileService.uploadBatch(files, bizType, StpUtil.getLoginIdAsLong()));
    }
}
