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
import com.litisle.litislefile.domain.entity.FileRef;
import com.litisle.litislefile.enums.SafeFlagEnum;
import com.litisle.litislefile.mapper.FileMetaMapper;
import com.litisle.litislefile.mapper.FileRefMapper;
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
 * 审核联动域实现（由 FileServiceImpl 按业务域拆分）：违规图隔离/恢复 + 隔离图到期软删 +
 * 引用解绑 + safe_flag 回写 + safe_flag 批量读取 + 机审取图（内部契约）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileModerationServiceImpl implements FileModerationService {

    private final FileMetaMapper fileMetaMapper;
    private final FileRefMapper fileRefMapper;
    private final SeaweedFsClient seaweedFsClient;
    private final FileProperties fileProperties;
    private final FileMetaSupport fileMetaSupport;

    /**
     * 违规图隔离（审核链路：moderation 侧 REJECT 时经 FileClient.feign 调用）
     */
    @Override
    public void quarantine(String fileId) {
        // 查活跃元数据后进入隔离/恢复统一实现
        moveBetweenQuarantinePrefix(fileMetaSupport.getMetaOrThrow(fileId), true);
    }

    /**
     * 隔离恢复（申诉链路：改判 PASS 时经 FileClient.feign 调用）：
     * 活跃记录走既有前缀移回；到期软删记录（del_flag=1，缓冲期内）走软删态恢复
     */
    @Override
    public void unquarantine(String fileId) {
        // 回查含软删记录：到期软删进入缓冲期的隔离图仍可完整恢复（对象还在 quarantine/ 前缀）
        FileMeta meta = fileMetaSupport.getMetaIncludeDeletedOrThrow(fileId);
        if (meta.getDelFlag() == DelFlag.DELETED) {
            restoreSoftDeleted(meta);
            return;
        }
        // 活跃记录：既有恢复路径（前缀移回 + safe_flag=1）
        moveBetweenQuarantinePrefix(meta, false);
    }

    /**
     * 隔离图到期软删（6-3.2，review 侧 QuarantineSweeper 保留期收口经 FileClient.feign 调用）：
     *
     * 不删 SeaweedFS 对象：软删 del_flag=1 + delete_time=now 退出活跃池/秒传判重池
     * + 按 fileId 解绑全部引用；对象与元数据行在软删缓冲期内保留（physical-clean-delay-days=7），
     * 缓冲期内申诉改判成功可完整恢复（unquarantine 支持软删态恢复），
     * 期满由对账任务统一物理清理（与其他文件同一套删除纪律，无回收站兜底）
     * <p>幂等双闸口：① 元数据不存在（已物理清理/异常数据）直接跳过；
     * ② 已软删（重复指令/重扫重投）直接跳过——删除指令对缺失/已删数据天然幂等</p>
     */
    @Override
    public void deleteQuarantined(String fileId) {
        // 幂等闸口①：查元数据（不过滤 del_flag，已删记录也能命中走闸口②）
        FileMeta meta = fileMetaMapper.selectOne(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId));
        if (meta == null) {
            log.info("隔离图删除跳过：元数据不存在，fileId={}", fileId);
            return;
        }
        // 幂等闸口②：已软删（重复指令/MQ 重投/重扫）直接跳过
        if (meta.getDelFlag() == DelFlag.DELETED) {
            log.info("隔离图删除跳过：已处于删除态，fileId={}", fileId);
            return;
        }
        // 前缀校验（防误删正常文件）：必须已在 quarantine/ 前缀才允许删除（前缀 SSOT 与隔离/对账同源）
        String prefix = fileProperties.getReconcileTask().getQuarantinePrefix();
        if (meta.getPath() == null || !meta.getPath().startsWith(prefix)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件不在隔离前缀，拒绝删除，fileId=" + fileId);
        }
        // 软删元数据（del_flag=1 + delete_time=now）：退出活跃池与秒传判重池；
        // 对象保留在 quarantine/ 前缀，缓冲期内可恢复，期满由对账任务统一物理清理
        fileMetaMapper.updateById(new FileMeta()
                .setId(meta.getId())
                .setDelFlag(DelFlag.DELETED)
                .setDeleteTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now()));
        // 解绑全部引用：审核终结违规证据属管理动作，引用登记一并清空
        // （对账物理清理的 file_ref 前置校验因此放行，不会因残留引用卡住）
        unbindAllRefs(fileId);
        log.info("隔离图到期软删完成（对象保留，缓冲期内可恢复），fileId={}，path={}", fileId, meta.getPath());
    }

    /**
     * 按 fileId 解绑全部引用（审核侧专用，隔离图到期软删时调用）：
     * 突破「四元组精确解绑」铁律按 fileId 全删——铁律保护的是正常业务流防误删他人引用；
     * 审核终结违规证据属管理动作，file_ref 为纯计数登记，按 fileId 全删不伤业务数据
     */
    @Override
    public void unbindAllRefs(String fileId) {
        // 按 fileId 全删引用登记行，返回解绑行数仅留痕
        int deleted = fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId));
        log.info("按 fileId 解绑全部引用完成，fileId={}，解绑行数={}", fileId, deleted);
    }

    /**
     * 软删态恢复（缓冲期内申诉改判成功）：到期软删的隔离图完整恢复——
     * 恢复前验对象存在 → 前缀移回 + safe_flag=1 → del_flag=0 + 清空 delete_time
     */
    private void restoreSoftDeleted(FileMeta meta) {
        // 恢复前验证对象存在（防「对象已物理清理但元数据行残留」边缘态）：
        // 对象不在则抛 FILE_NOT_FOUND，由调用方（content 侧申诉消费）try-catch 兜底，文章照常恢复
        List<String> keys = seaweedFsClient.listKeys(meta.getBucket(), meta.getPath());
        if (keys.isEmpty()) {
            log.warn("隔离图软删态恢复失败：对象已不存在，fileId={}，path={}", meta.getFileId(), meta.getPath());
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        // 前缀移回（quarantine/ → 原前缀）+ safe_flag=1，复用隔离/恢复统一实现
        moveBetweenQuarantinePrefix(meta, false);
        // 解除软删态：del_flag=0 回活跃池/秒传判重池 + 清空 delete_time
        // （显式 set null：updateById 默认忽略 null 字段，清空 delete_time 必须走 UpdateWrapper）
        fileMetaMapper.update(null, new LambdaUpdateWrapper<FileMeta>()
                .eq(FileMeta::getId, meta.getId())
                .set(FileMeta::getDelFlag, DelFlag.NOT_DELETED)
                .set(FileMeta::getDeleteTime, null)
                .set(FileMeta::getUpdateTime, LocalDateTime.now()));
        log.info("隔离图软删态恢复完成，fileId={}", meta.getFileId());
    }

    /**
     * 隔离/恢复统一实现（S3 无原生 move：copy + delete 组合；两步均幂等，中途失败重试自愈）
     *
     *
     *
     * <p>前缀 SSOT：与对账任务清理共用 litisle.file.reconcile-task.quarantine-prefix（防两处配置漂移）。
     * 移入/移回按前缀可逆推导目标 key（隔离 = quarantine/ + 原 key），无需新增列记录原路径；
     * del_flag 全程保持 0，不破坏 file_ref 不变式（隔离图不是删除，是暂存证据）</p>
     *
     * @param meta 已查出的文件元数据（调用方按场景选择活跃/含软删查询）
     */
    private void moveBetweenQuarantinePrefix(FileMeta meta, boolean toQuarantine) {
        String prefix = fileProperties.getReconcileTask().getQuarantinePrefix();
        boolean quarantined = meta.getPath() != null && meta.getPath().startsWith(prefix);
        // 幂等闸口：重复隔离 / 重复恢复直接跳过（调用方与 MQ 重投均安全）
        if (toQuarantine == quarantined) {
            log.info("文件已处于目标状态（quarantined={}），跳过重复操作，fileId={}", quarantined, meta.getFileId());
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
        log.info("违规图{}完成，fileId={}，path：{} → {}", toQuarantine ? "隔离" : "恢复", meta.getFileId(), meta.getPath(), targetPath);
    }

    /**
     * 审核结果回写 safe_flag（file 侧 MQ 消费入口）：按主键定点 update，幂等（已处于目标态直接跳过，
     *
     * 根据 filedID 与 审核结果 更新MySQL 文件元数据
     *
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
     *
     * 根据fileId，从MySQL批量查询文件审核状态
     *
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
     * 批量查询 sha256（file 客户端契约提供方，板块三 3.2）：
     *
     * 根据 fileId 列表，从 MySQL 批量查询文件 sha256 指纹
     *
     * select 只取两列降 IO；仅查活跃记录（del_flag=0）；任一 fileId 无活跃记录
     * 抛 FILE_NOT_FOUND fail-fast（调用方为审核链路，缺指纹则聚合指纹无法计算，
     * 静默降级会致指纹错配被 review 侧丢弃事件 = 漏审，宁严勿松）
     */
    @Override
    public Map<String, String> batchGetSha256(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Map.of();
        }
        List<FileMeta> metas = fileMetaMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .select(FileMeta::getFileId, FileMeta::getSha256)
                .in(FileMeta::getFileId, fileIds)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED));
        Map<String, String> result = metas.stream()
                .filter(m -> m.getSha256() != null)
                .collect(Collectors.toMap(FileMeta::getFileId, FileMeta::getSha256));
        // 逐一核验：任一 fileId 无活跃记录/无指纹 → fail-fast（对齐审核链路宁严勿松口径）
        for (String fileId : fileIds) {
            if (!result.containsKey(fileId)) {
                throw new BizException(ErrorCode.FILE_NOT_FOUND);
            }
        }
        return result;
    }

    /**
     * 机审取图（file 客户端契约提供方）：不过 safe_flag 闸口（申诉复核需重取隔离图
     *
     * 根据fileId，从seaweedfs查询文件数据
     *
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