package com.litisle.litislefile.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.commonredis.ratelimit.RateLimiter;
import com.litisle.litislefile.domain.dto.FileDownloadResource;
import com.litisle.litislefile.domain.vo.FileMetaVO;
import com.litisle.litislefile.domain.vo.FileUploadVO;
import com.litisle.litislefile.domain.vo.PresignVO;
import com.litisle.litislefile.domain.vo.ThumbnailVO;
import com.litisle.litislefile.enums.BizTypeEnum;
import com.litisle.litislefile.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * | GET    | /{fileId}/download  | 流式代理下载（Range 断点续传，200/206/416）   | 登录(PUBLIC)；PRIVATE 需 owner/ADMIN |
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
 * - 大文件下载默认走直连范式：getUrl/presign 返回的 URL 即下载凭证，客户端直连对象存储，
 *   断点续传由 SeaweedFS S3 网关原生 Range(206) 支持；仅“必须经服务端中转”场景（鉴权收紧/防直链/水印）
 *   走 GET /{fileId}/download 流式代理端点（手动透传 Range，支持 200/206/416，详见 FileService#download）
 * - /{fileId}/download 是唯一非 Result<T> 返回的端点：返回 StreamingResponseBody 异步写二进制流 + 状态码
 *   （阻塞 IO 隔离到 fileIoExecutor，慢客户端不占 Tomcat 线程）；
 *   出错（FILE_NOT_FOUND/NO_PERMISSION）仍由全局异常处理器返回 JSON
 * - 引用登记入口（P0 已落地）：跨服务业务方经 FileClient（Feign，/api/feign/file/ref/bind|unbind，
 *   FileFeignProviderController 提供）与业务表同事务登记；路径前缀网关未路由，仅服务间可达
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
                                                  @RequestParam("bizType") BizTypeEnum bizType) {
        // 逐个委托单文件链路；中途失败已成功的不回滚，部分成功由调用方处理
        return Result.success(fileService.uploadBatch(files, bizType, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 取 URL：PUBLIC→直链；PRIVATE→预签名（30min，owner/ADMIN 校验）
     */
    @Operation(summary = "获取访问 URL", description = "PUBLIC 登录即可；PRIVATE 需 owner/ADMIN，口径与 presign 统一（审查表 #6）")
    @GetMapping("/{fileId}/url")
    @SaCheckLogin
    public Result<String> getUrl(@PathVariable("fileId") String fileId) {
        // Service 内部分流：PUBLIC 拼直链 / PRIVATE 校验 owner/ADMIN 后预签名
        return Result.success(fileService.getUrl(fileId, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 流式代理下载（支持 HTTP Range 断点续传：200 全量 / 206 部分内容 / 416 区间非法）
     * <p>默认下载走直连范式（/url、/presign）；本端点仅用于“必须经服务端中转”场景。
     * ⚠️ 唯一非 Result&lt;T&gt; 返回的端点：直接写二进制流（Accept-Ranges/ETag/Content-Range 头），
     * 出错仍由全局异常处理器返回 JSON。</p>
     *
     * 本方法只做「协议分发」——把 Service 返回的 FileDownloadResource 翻译成 HTTP 响应：
     *
     * resource 形态        判断条件                  状态码    响应头
     * 区间非法              rangeInvalid() == true    416       Content-Range: bytes *&#47;totalSize
     * 部分内容              partial() == true         206       Content-Range: bytes s-e/totalSize
     * 全量                  partial() == false        200       仅 Content-Length
     *
     * 公共头（Accept-Ranges/ETag/Content-Type/Content-Disposition）在分流前统一写入；
     * 416 提前 return 不写 body，200/206 才流式回写（只关对象流，不关 Servlet 流）。
     */
    @Operation(summary = "流式下载（断点续传）", description = "服务端代理下载，手动透传 Range：206 部分内容 / 416 区间非法；"
            + "PRIVATE 文件需 owner/ADMIN。常规下载仍建议走 /url 或 /presign 直连对象存储")
    @RateLimiter(time = DOWNLOAD_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = DOWNLOAD_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @GetMapping("/{fileId}/download")
    @SaCheckLogin
    public StreamingResponseBody download(@PathVariable("fileId") String fileId,
                         @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
                         HttpServletResponse response) {
        //1：Service 内完成鉴权 + Range 解析，返回流式资源（stream 由本方法 try-with-resources 关闭）
        FileDownloadResource resource = fileService.download(fileId, StpUtil.getLoginIdAsLong(), range);

        /**
         * 举例：resource = {partial=true, rangeInvalid=false, start=1000, end=1999, totalSize=5000}
         *
         * // 第1步：先写公共头（200/206/416 三种响应都要有）
         * Accept-Ranges: bytes / ETag: "sha256..." / Content-Type: image/jpeg / Content-Disposition: ...
         *
         * // 第2步：分流状态码
         * rangeInvalid? 否 → 继续；partial? 是 → 206
         * Content-Range: bytes 1000-1999/5000
         *
         * // 第3步：Content-Length = 1999 - 1000 + 1 = 1000（本次实际字节数，不是 5000！）
         *
         * // 第4步：流式回写（8KB 缓冲），只关对象流不关 Servlet 流
         * transferTo(response.getOutputStream())
         */

        //2：先写公共头：Accept-Ranges 告知客户端支持断点续传；ETag 取 sha256（对象内容不变，续传一致）
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        if (resource.etag() != null) {
            response.setHeader(HttpHeaders.ETAG, "\"" + resource.etag() + "\"");
        }
        if (resource.contentType() != null) {
            response.setContentType(resource.contentType());
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(resource.fileName()));

        //3：形态一（416）：区间不可满足，仅回 totalSize 供客户端收敛重试；返回 null 表示无 body
        //   （StreamingResponseBody 为 null 时 Spring 标记请求已处理，不再写 body，状态码/头保留）
        if (resource.rangeInvalid()) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + resource.totalSize());
            return null;
        }

        //4：Content-Length 为本次实际返回字节数（全量或部分区间），不可写成 totalSize
        response.setContentLengthLong(resource.end() - resource.start() + 1);

        //5：形态二（206）：部分内容，声明本次区间；形态三（200）：全量
        if (resource.partial()) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + resource.start() + "-" + resource.end() + "/" + resource.totalSize());
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }

        //6：返回 StreamingResponseBody：阻塞的 S3 读 + 客户端写交给 fileIoExecutor（见 FileAsyncConfig），
        //   脱离 Tomcat 工作线程，防慢客户端占满主线程池；
        //   try-with-resources 关闭对象流防泄漏（Servlet 流只 flush 不 close）
        return out -> {
            try (InputStream in = resource.stream()) {
                in.transferTo(out);
                out.flush();
            }
        };
    }

    /** Content-Disposition：ASCII 回退 + filename* UTF-8 编码，兼容中文文件名 */
    private String buildContentDisposition(String fileName) {
        String name = (fileName == null || fileName.isBlank()) ? "download" : fileName;
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded;
    }

    /**
     * 强制预签名（owner/ADMIN；expireMinutes ≤ 配置上限，防 99999 绕过 LICENSE 限时）
     */
    @Operation(summary = "强制预签名", description = "仅 PRIVATE 文件；expireMinutes 空取配置默认，上限同配置")
    @RateLimiter(time = PRESIGN_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = PRESIGN_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @PostMapping("/{fileId}/presign")
    @SaCheckLogin
    public Result<PresignVO> presign(@PathVariable("fileId") String fileId,
                                     @RequestParam(value = "expireMinutes", required = false) Integer expireMinutes) {
        // expireMinutes 为空取配置默认；Service 内二次校验 ≤ 配置上限
        return Result.success(fileService.presign(fileId, expireMinutes, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 元数据查询（mime/size/宽高/align）
     */
    @Operation(summary = "文件元数据", description = "登录即可查询；非 owner/ADMIN 脱敏 ownerId；文件不存在抛 FILE_NOT_FOUND")
    @GetMapping("/{fileId}/meta")
    @SaCheckLogin
    public Result<FileMetaVO> getMeta(@PathVariable("fileId") String fileId) {
        // 非 owner/ADMIN 由 Service 脱敏 ownerId
        return Result.success(fileService.getMeta(fileId, StpUtil.getLoginIdAsLong()));
    }

    /**
     * 缩略图 URL（w/h 可选，默认 yml 配置；按需生成，不落库）
     */
    @Operation(summary = "获取缩略图 URL", description = "w/h 可选（默认 300×300，上限 2000）；非图片拒绝")
    @RateLimiter(time = THUMBNAIL_WINDOW_SECONDS, timeUnit = TimeUnit.SECONDS,
            count = THUMBNAIL_MAX_COUNT, keyType = RateLimiter.KeyType.USER)
    @PostMapping("/{fileId}/thumbnail")
    @SaCheckLogin
    public Result<ThumbnailVO> thumbnail(@PathVariable("fileId") String fileId,
                                         @RequestParam(value = "w", required = false) Integer width,
                                         @RequestParam(value = "h", required = false) Integer height) {
        // w/h 为空取 yml 默认 300×300；缩略图按需生成派生 key，不落库
        return Result.success(fileService.getThumbnail(fileId, width, height));
    }

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
}
