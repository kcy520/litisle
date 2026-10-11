package com.litisle.litislefile.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.dto.file.FileContentDTO;
import com.litisle.litislefile.client.SeaweedFsClient;
import com.litisle.litislefile.config.FileProperties;
import com.litisle.litislefile.domain.entity.FileMeta;
import com.litisle.litislefile.enums.SafeFlagEnum;
import com.litisle.litislefile.mapper.FileMetaMapper;
import com.litisle.litislefile.service.FileModerationService;
import com.litisle.litislefile.service.support.FileMetaSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 审核联动域实现（由 FileServiceImpl 按业务域拆分）：违规图隔离/恢复 + safe_flag 回写 +
 * safe_flag 批量读取 + 机审取图（内部契约）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileModerationServiceImpl implements FileModerationService {

    private final FileMetaMapper fileMetaMapper;
    private final SeaweedFsClient seaweedFsClient;
    private final FileProperties fileProperties;
    private final FileMetaSupport fileMetaSupport;

    /**
     * 违规图隔离（审核链路：moderation 侧 REJECT 时经 FileClient.feign 调用）
     */
    @Override
    public void quarantine(String fileId) {
        moveBetweenQuarantinePrefix(fileId, true);
    }

    /**
     * 隔离恢复（申诉链路：改判 PASS 时经 FileClient.feign 调用）
     */
    @Override
    public void unquarantine(String fileId) {
        moveBetweenQuarantinePrefix(fileId, false);
    }

    /**
     * 隔离/恢复统一实现（S3 无原生 move：copy + delete 组合；两步均幂等，中途失败重试自愈）
     * <p>前缀 SSOT：与对账任务跳过清理共用 litisle.file.reconcile-task.quarantine-prefix（防两处配置漂移）。
     * 移入/移回按前缀可逆推导目标 key（隔离 = quarantine/ + 原 key），无需新增列记录原路径；
     * del_flag 全程保持 0，不破坏 file_ref 不变式（隔离图不是删除，是暂存证据）</p>
     */
    private void moveBetweenQuarantinePrefix(String fileId, boolean toQuarantine) {
        // 查活跃元数据（del_flag=0，不存在抛 FILE_NOT_FOUND）
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);
        String prefix = fileProperties.getReconcileTask().getQuarantinePrefix();
        boolean quarantined = meta.getPath() != null && meta.getPath().startsWith(prefix);
        // 幂等闸口：重复隔离 / 重复恢复直接跳过（调用方与 MQ 重投均安全）
        if (toQuarantine == quarantined) {
            log.info("文件已处于目标状态（quarantined={}），跳过重复操作，fileId={}", quarantined, fileId);
            return;
        }
        // 本体 + 全部缩略图派生 key（_thumb_*）一并移动，防隔离后缩略图仍可经旧 key 直链访问
        List<String> keys = seaweedFsClient.listKeys(meta.getBucket(), meta.getPath());
        for (String key : keys) {
            // 每个对象 key 按同一前缀规则变换：移入 = quarantine/ + key；移回 = 去掉 quarantine/ 前缀
            String targetKey = toQuarantine ? prefix + key : key.substring(prefix.length());
            // 先 copy 后 delete：copy 同 key 覆盖幂等、delete 对不存在 key 也成功，重试安全
            seaweedFsClient.copyObject(meta.getBucket(), key, targetKey);
            seaweedFsClient.delete(meta.getBucket(), key);
        }
        // 存储移动完成后回写元数据：path 指向新前缀 + safe_flag（隔离=0 违规 / 恢复=1 正常）
        String targetPath = toQuarantine ? prefix + meta.getPath() : meta.getPath().substring(prefix.length());
        fileMetaMapper.updateById(new FileMeta()
                .setId(meta.getId())
                .setPath(targetPath)
                .setSafeFlag(toQuarantine ? SafeFlagEnum.VIOLATIVE : SafeFlagEnum.NORMAL)
                .setUpdateTime(LocalDateTime.now()));
        log.info("违规图{}完成，fileId={}，path：{} → {}", toQuarantine ? "隔离" : "恢复", fileId, meta.getPath(), targetPath);
    }

    /**
     * 审核结果回写 safe_flag（file 侧 MQ 消费入口）：按主键定点 update，幂等（已处于目标态直接跳过，
     * MQ 重投安全）；REJECT 分支不走本方法（走 quarantine() 含物理隔离）
     */
    public void updateSafeFlag(String fileId, SafeFlagEnum target) {
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);
        // 幂等闸口：重复事件/重投直接跳过
        if (meta.getSafeFlag() == target) {
            log.info("safe_flag 已处于目标状态，跳过回写，fileId={}，target={}", fileId, target);
            return;
        }
        int affected = fileMetaMapper.update(null, new LambdaUpdateWrapper<FileMeta>()
                .eq(FileMeta::getId, meta.getId())
                .set(FileMeta::getSafeFlag, target)
                .set(FileMeta::getUpdateTime, LocalDateTime.now()));
        if (affected == 0) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        log.info("safe_flag 回写完成，fileId={}，{} → {}", fileId, meta.getSafeFlag(), target);
    }

    /**
     * 批量读取 safe_flag（file 客户端契约提供方）：仅查活跃记录（del_flag=0），
     * select 只取两列降 IO；查不到的 fileId 不在 Map 中，由调用方按未知态保守处理
     */
    @Override
    public Map<String, Integer> batchGetSafeFlags(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        List<FileMeta> metas = fileMetaMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .select(FileMeta::getFileId, FileMeta::getSafeFlag)
                .in(FileMeta::getFileId, fileIds)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED));
        return metas.stream()
                .collect(Collectors.toMap(FileMeta::getFileId, m -> m.getSafeFlag().getCode()));
    }

    /**
     * 机审取图（file 客户端契约提供方）：不过 safe_flag 闸口（申诉复核需重取隔离图
     * safe_flag=0）；seaweedFsClient.get 整读（≤10MB，与上传同步瓶颈同源）
     */
    @Override
    public FileContentDTO getInternalContent(String fileId) {
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);
        byte[] bytes = seaweedFsClient.get(meta.getBucket(), meta.getPath());
        FileContentDTO dto = new FileContentDTO();
        dto.setFileId(meta.getFileId());
        dto.setMime(meta.getMime());
        dto.setContent(bytes);
        return dto;
    }
}