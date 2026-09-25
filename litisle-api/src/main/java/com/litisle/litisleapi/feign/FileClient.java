package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.file.FileOwnerDTO;
import com.litisle.litisleapi.dto.file.FileRefDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

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
}
