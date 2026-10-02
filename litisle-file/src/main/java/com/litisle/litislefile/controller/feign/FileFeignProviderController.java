package com.litisle.litislefile.controller.feign;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.file.FileOwnerDTO;
import com.litisle.litisleapi.dto.file.FileRefDTO;
import com.litisle.litisleapi.feign.FileClient;
import com.litisle.litislefile.domain.vo.FileMetaVO;
import com.litisle.litislefile.enums.StorageBizTypeEnum;
import com.litisle.litislefile.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * FileClient 契约实现（Provider 端，仅供服务间 Feign 调用）
 *
 * 【设计说明】
 * 1. implements FileClient —— 路径由接口上的映射注解决定，实现类无需重复标注；
 *    路径前缀 /api/feign/file 网关未路由，外部请求不可达，仅服务间可用（规范自带防护）
 * 2. 不加 @SaCheckLogin：Feign 服务端线程无用户会话，鉴权由"网关不路由 + 仅服务内可达"保证；
 *    ownerId 由调用方服务传入（调用方已从自身登录态取得，不信任外部请求）
 * 3. 只做"参数转换 + 委托 FileService + 包装 Result"，不写业务逻辑；
 *    bind/unbind 与业务表同事务由调用方保证（Seata AT，调用方 @GlobalTransactional）
 * 4. 文件不存在 → FileService 抛 BizException(FILE_NOT_FOUND) → 全局处理器转 Result.fail，
 *    Feign 正常解码不触发降级，调用方判码处理
 *
 * @author litisle
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class FileFeignProviderController implements FileClient {

    private final FileService fileService;

    /**
     * 查询文件归属信息（不脱敏，仅服务间）：委托 Service 查活跃元数据
     */
    @Override
    public Result<FileOwnerDTO> getFileInfo(String fileId) {
        log.info("远程api调用getFileInfo fileId:{}", fileId);
        FileMetaVO meta = fileService.getInternalMeta(fileId);
        FileOwnerDTO dto = new FileOwnerDTO();
        dto.setFileId(meta.getFileId());
        dto.setOwnerId(meta.getOwnerId());
        dto.setBizType(meta.getBizType().getName());
        return Result.success(dto);
    }

    /**
     * 登记引用（幂等）：String bizType → DictEnum 三值兼容解析（枚举名/code/描述均接受）
     */
    @Override
    public Result<Void> bindRef(FileRefDTO dto) {
        log.info("远程api调用bindRef fileId:{} bizType:{} bizId:{} ownerId:{}",
                dto.getFileId(), dto.getBizType(), dto.getBizId(), dto.getOwnerId());
        fileService.bindRef(dto.getFileId(), parseBizType(dto.getBizType()), dto.getBizId(), dto.getOwnerId());
        return Result.success();
    }

    /**
     * 解除引用（幂等）：按四元组精确定位仅删当前调用方引用行
     */
    @Override
    public Result<Void> unbindRef(FileRefDTO dto) {
        log.info("远程api调用unbindRef fileId:{} bizType:{} bizId:{} ownerId:{}",
                dto.getFileId(), dto.getBizType(), dto.getBizId(), dto.getOwnerId());
        fileService.unbindRef(dto.getFileId(), parseBizType(dto.getBizType()), dto.getBizId(), dto.getOwnerId());
        return Result.success();
    }

    /**
     * 批量查询 fileId → URL 映射（Feign 内部调用，防列表页 N+1）
     * <p>⚠️ 本方法不做 owner/ADMIN 校验：仅限服务间调用（网关未路由 /api/feign/**），
     * 若未来暴露 REST 必须补 operatorId 做权限校验（见 FileService#batchGetUrls 注释）
     */
    @Override
    public Result<Map<String, String>> batchGetUrls(List<String> fileIds) {
        log.info("远程api调用batchGetUrls size:{}", fileIds == null ? 0 : fileIds.size());
        return Result.success(fileService.batchGetUrls(fileIds));
    }

    /**
     * bizType 字符串 → 枚举（DictEnum 三值兼容：枚举名/code/中文描述均可，忽略大小写）
     */
    private StorageBizTypeEnum parseBizType(String bizType) {
        return Arrays.stream(StorageBizTypeEnum.values())
                .filter(e -> e.getName().equalsIgnoreCase(bizType)
                        || e.getCode().equalsIgnoreCase(bizType)
                        || e.getDesc().equals(bizType))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR, "未知的文件业务类型: " + bizType));
    }
}
