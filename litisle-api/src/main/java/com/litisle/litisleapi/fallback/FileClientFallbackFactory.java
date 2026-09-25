package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.file.FileOwnerDTO;
import com.litisle.litisleapi.dto.file.FileRefDTO;
import com.litisle.litisleapi.feign.FileClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * FileClient 降级工厂
 *
 * 【降级语义】全方法 fail-fast（Api-Architecture §4.1 写操作降级规范）：
 * getFileInfo 是引用链路的归属校验闸口、bind/unbind 是核心写操作，
 * 静默降级为成功会导致"未校验归属就挂引用 / 引用漏登记 → 秒传共享文件被误删"，
 * 异常必须穿透到调用方，由全局异常处理器兜底提示
 *
 * @author litisle
 */
@Slf4j
public class FileClientFallbackFactory implements FallbackFactory<FileClient> {

    @Override
    public FileClient create(Throwable cause) {
        return new FileClient() {

            // fail-fast（快速失败）是一种容错设计策略
            // 一旦检测到异常或不可接受的降级结果，立即抛出异常、终止当前流程，而不是返回一个“看起来正常”的默认值或静默成功。

            /**
             * 归属校验读操作同样 fail-fast：降级返回 null 会让调用方误判"文件不存在"或绕过校验
             */
            @Override
            public Result<FileOwnerDTO> getFileInfo(String fileId) {
                log.error("远程调用 file 模块 getFileInfo 失败！fileId = {}", fileId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "文件服务暂不可用，请稍后重试", cause);
            }

            /**
             * 核心写操作 fail-fast：降级返回成功会导致引用漏登记（P0 场景的根因），禁止静默降级
             */
            @Override
            public Result<Void> bindRef(FileRefDTO dto) {
                log.error("远程调用 file 模块 bindRef 失败！fileId = {}, bizType = {}, bizId = {}",
                        dto.getFileId(), dto.getBizType(), dto.getBizId(), cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "文件服务暂不可用，引用登记失败，请稍后重试", cause);
            }

            /**
             * 核心写操作 fail-fast：与 bindRef 同理（是否忽略解绑失败由调用方按场景决定）
             */
            @Override
            public Result<Void> unbindRef(FileRefDTO dto) {
                log.error("远程调用 file 模块 unbindRef 失败！fileId = {}, bizType = {}, bizId = {}",
                        dto.getFileId(), dto.getBizType(), dto.getBizId(), cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "文件服务暂不可用，引用解除失败，请稍后重试", cause);
            }
        };
    }
}
