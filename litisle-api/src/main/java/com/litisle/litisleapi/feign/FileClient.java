package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.file.FileContentDTO;
import com.litisle.litisleapi.dto.file.FileOwnerDTO;
import com.litisle.litisleapi.dto.file.FileRefDTO;
import com.litisle.litisleapi.fallback.FileClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

/**
 * 文件服务 Feign 客户端（跨服务契约）
 *
 * 【设计说明】
 * 1. 只定义接口不写实现 —— 提供方在 litisle-file 模块实现本接口暴露端点
 * 2. 路径前缀遵循规范 /api/feign/file：网关未注册该前缀路由，外部请求不可达，仅服务间可用
 *    （对应 next_update.md P0"网关拉黑外部直访"诉求，规范路径天然满足）
 * 3. bind/unbind 属核心写操作（file_ref 引用登记），降级 fail-fast，见 FileClientFallbackFactory
 * 4. bindRef 幂等（同四元组已登记则跳过），调用方重试安全
 *
 * 【调用方】user 模块（设置头像）、未来 content（封面/正文图登记）
 *
 * @author litisle
 */
@FeignClient(
        name = "litisle-file",
        configuration = FeignApiConfig.class,
        fallbackFactory = FileClientFallbackFactory.class
)
public interface FileClient {

    /**
     * 查询文件归属信息（不脱敏，仅服务间）
     * <p>调用方做归属校验：file_meta.owner_id == 当前登录人（防挂他人文件）
     *
     * @param fileId 文件ID
     * @return 归属信息；文件不存在时提供方返回 Result.fail(FILE_NOT_FOUND)，调用方判码处理
     */
    @GetMapping("/api/feign/file/{fileId}/info")
    Result<FileOwnerDTO> getFileInfo(@PathVariable("fileId") String fileId);

    /**
     * 登记引用（幂等）：同四元组已存在则跳过
     * <p>调用方需与业务表同事务（Seata AT 接入后 @GlobalTransactional 保证跨服务回滚）
     */
    @PostMapping("/api/feign/file/ref/bind")
    Result<Void> bindRef(@RequestBody FileRefDTO dto);

    /**
     * 解除引用（幂等）：按四元组精确定位仅删当前调用方引用行，行不存在静默结束
     */
    @PostMapping("/api/feign/file/ref/unbind")
    Result<Void> unbindRef(@RequestBody FileRefDTO dto);

    /**
     * 批量查询 fileId → URL 映射（一次往返，避免列表页 N+1）
     * <p>调用方：需批量渲染文件直链的业务服务（如文章列表封面/正文图）；
     * 属展示类读操作，降级静默返回空 Map（列表页降级为占位图，不阻断主流程）
     *
     * @param fileIds 文件ID列表（空列表提供方直接返回空 Map）
     * @return fileId → 访问URL 映射（PUBLIC 直链 / PRIVATE 预签名，分流对调用方透明）
     */
    @PostMapping("/api/feign/file/batch-urls")
    Result<Map<String, String>> batchGetUrls(@RequestBody List<String> fileIds);

    /**
     * 违规图隔离（审核 REJECT 时调用）：对象移入 quarantine/ 前缀 + safe_flag=0（幂等）
     * <p>核心审核写操作，降级 fail-fast（静默成功会致违规图未隔离、证据链断裂）；
     * del_flag 保持 0，file_ref 不变式不破坏
     *
     * @param fileId 文件ID
     */
    @PostMapping("/api/feign/file/{fileId}/quarantine")
    Result<Void> quarantine(@PathVariable("fileId") String fileId);

    /**
     * 隔离恢复（申诉改判 PASS 时调用）：对象移回原前缀 + safe_flag 回 1（幂等）
     * <p>核心审核写操作，降级 fail-fast（与 quarantine 对称）
     *
     * @param fileId 文件ID
     */
    @PostMapping("/api/feign/file/{fileId}/unquarantine")
    Result<Void> unquarantine(@PathVariable("fileId") String fileId);

    /**
     * 隔离图到期删除（6-3.2 review 侧 QuarantineSweeper 保留期收口时调用）：
     * path 前缀校验（必须已在 quarantine/ 前缀，防误删正常文件）→ 删本体+缩略图
     * （SeaweedFS 回收站兜底 7 天）→ del_flag=1 业务软删
     * <p>核心审核写操作，降级 fail-fast（静默成功会致保留期收口假完成、隔离图永久占存）</p>
     *
     * @param fileId 文件ID
     */
    @PostMapping("/api/feign/file/{fileId}/delete-quarantined")
    Result<Void> deleteQuarantined(@PathVariable("fileId") String fileId);

    /**
     * 批量读取 safe_flag（review 复审聚合/申诉恢复用，禁止跨库直查 file_meta）
     * <p>value 为 safe_flag code（0-违规/1-正常/2-待审/3-存疑，SSOT 为 file 侧 SafeFlagEnum，
     * 契约库不反向依赖 file 模块故传 int）；仅返回活跃记录（del_flag=0），
     * 查不到的 fileId 不在 Map 中，调用方须视为未知态保守处理（不唤醒/保持占位）
     * <p>读操作，降级静默返回空 Map（见 FileClientFallbackFactory），调用方严禁把
     * 「读不到」当「safe_flag=1 放行」
     *
     * @param fileIds 文件ID列表（空列表提供方直接返回空 Map）
     */
    @PostMapping("/api/feign/file/batch-safe-flags")
    Result<Map<String, Integer>> batchGetSafeFlags(@RequestBody List<String> fileIds);

    /**
     * 批量查询 fileId → sha256 映射（板块三 3.2 配图复审聚合指纹计算用）
     * <p>调用方：content 发布链路（免审连带校验 + image.submit 事件体聚合指纹）；
     * 审核链路核心读依赖，降级 fail-fast（取不到 sha256 则聚合指纹无法计算，
     * 静默降级会让指纹错配被 review 侧丢弃事件 = 漏审）
     *
     * @param fileIds 文件ID列表（空列表提供方直接返回空 Map）
     * @return fileId → sha256（小写十六进制，file_meta.sha256 口径）
     */
    @PostMapping("/api/feign/file/batch-sha256")
    Result<Map<String, String>> batchGetSha256(@RequestBody List<String> fileIds);

    /**
     * 机审取图契约端点（字节流形态，SSRF 防护唯一入口）
     * <p>机审取图一律按 fileId 走本端点取原始字节流，严禁按用户 URL 抓取（Review-Plan
     * §4.2.2 取图纪律）；云 API 审核同样优先传字节流而非内网 URL。byte[] 经 Jackson
     * 序列化为 Base64（≤10MB 图片内网传输可接受），不签发内网 presign URL，
     * 杜绝「review 侧按 URL 请求」引入的 SSRF/内网探测面
     * <p>本端点不过 safe_flag 闸口：申诉复核需重取隔离图（safe_flag=0）；
     * 仅服务间可用（网关未路由 /api/feign/**），不占公开面访问配额；
     * 审核链路核心读依赖，降级 fail-fast（取不到字节流机审无法进行）
     *
     * @param fileId 文件ID
     */
    @GetMapping("/api/feign/file/{fileId}/content")
    Result<FileContentDTO> getContent(@PathVariable("fileId") String fileId);

}
