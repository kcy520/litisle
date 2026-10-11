package com.litisle.litislefile.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litislefile.domain.entity.FileMeta;
import com.litisle.litislefile.domain.entity.FileRef;
import com.litisle.litislefile.enums.StorageBizTypeEnum;
import com.litisle.litislefile.mapper.FileMetaMapper;
import com.litisle.litislefile.mapper.FileRefMapper;
import com.litisle.litislefile.service.FileManageService;
import com.litisle.litislefile.service.support.FileMetaSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 管理域实现（由 FileServiceImpl 按业务域拆分；方案C 引用登记/解绑/删除）：
 *
 * 【删除（方案C：解绑语义）】校验 owner/ADMIN →
 *    Step1 删 file_ref 当前调用方引用行（file_id + owner_id）
 *    Step2 file_ref 仍有其他活跃引用 → 结束（文件不动，他人引用不受影响）
 *    Step3 无活跃引用 → del_flag=1 + delete_time=now()（置 1 后退出秒传判重池）
 *    物理清理交对账任务（7 天缓冲，防误删不可恢复；方案C 设计见 seaweedfs笔记.md §3.4）
 * 【对账任务】@Scheduled 每日 02:00（多实例需 Redisson 分布式锁保证单实例执行）：
 *    Step1 补偿上传：扫 status=0 且超 30min → S3 head 有对象则置 status=1，无则 del_flag=1 废弃
 *    Step2 物理清理：扫 del_flag=1 且 delete_time 超 7 天 → 先校验 file_ref 无活跃引用
 *    （仍有引用 → 跳过并告警，引用方漏登记兜底不误删）→ 删 SeaweedFS 本体（含缩略图派生 key）
 *    → 物理删元数据 + 防御性同删该 file_id 的残留 file_ref 行
 *    （正常流程下此时应已无行——前置校验已过；此步仅防"解绑删行失败但 del_flag 已置位"的极端残留）
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileManageServiceImpl implements FileManageService {

    private final FileMetaMapper fileMetaMapper;
    private final FileRefMapper fileRefMapper;
    private final FileMetaSupport fileMetaSupport;

    /**
     * 引用登记（方案C）：幂等插入 file_ref（同四元组已存在则跳过）；与业务表同事务由调用方保证
     */
    @Override
    public void bindRef(String fileId, StorageBizTypeEnum bizType, Long bizId, Long ownerId) {
        // 前置校验：文件必须存在且未删除
        fileMetaSupport.getMetaOrThrow(fileId);
        // 幂等：同一引用（fileId+bizType+bizId+ownerId）已登记则跳过，业务方重试安全
        // 与业务表同事务由调用方保证（业务方方法上加 @Transactional）
        Long count = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId)
                .eq(FileRef::getBizType, bizType)
                .eq(FileRef::getBizId, bizId)
                .eq(FileRef::getOwnerId, ownerId));
        if (count > 0) {
            return;
        }
        try {
            fileRefMapper.insert(new FileRef()
                    .setFileId(fileId)
                    .setBizType(bizType)
                    .setBizId(bizId)
                    .setOwnerId(ownerId)
                    .setCreateTime(LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            // uk_ref(file_id,biz_type,biz_id,owner_id) 唯一约束兜底：并发下两请求均通过上面的
            // selectCount=0，后到者插入被 DB 拒绝；此处吞掉异常维持幂等语义，
            // 不标记回滚（MySQL 重复键错误仅使该条 insert 失败，当前事务可继续）
            log.info("引用登记并发重复，按幂等跳过：fileId={}，bizType={}，bizId={}，ownerId={}",
                    fileId, bizType, bizId, ownerId);
        }
    }

    /**
     * 解除引用登记（方案C）：按四元组精确定位仅删当前调用方引用行，不动文件本体；行不存在静默结束（幂等）
     */
    @Override
    public void unbindRef(String fileId, StorageBizTypeEnum bizType, Long bizId, Long ownerId) {
        // 前置校验：文件必须存在且未删除
        fileMetaSupport.getMetaOrThrow(fileId);
        // 仅删当前调用方的引用行（四条件精确定位），不动文件本体；幂等：行不存在静默结束
        fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId)
                .eq(FileRef::getBizType, bizType)
                .eq(FileRef::getBizId, bizId)
                .eq(FileRef::getOwnerId, ownerId));
    }

    /**
     * 删除（方案C 解绑语义）：owner/ADMIN 校验 → Step1 删当前调用方引用行 → Step2 仍有他人引用则不动文件
     * → Step3 无引用才软删（del_flag=1），物理清理交对账任务 7 天缓冲
     */
    @Override
    public void delete(String fileId, Long operatorId) {
        // 查活跃元数据 + owner/ADMIN 校验（删除属敏感操作）
        FileMeta meta = fileMetaSupport.getMetaOrThrow(fileId);
        checkOwnerOrAdmin(meta, operatorId);
        // Step1：仅解除当前调用方引用行（file_id + owner_id，不限 bizType——一次清完名下引用）
        fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId)
                .eq(FileRef::getOwnerId, operatorId));
        // Step2：仍有其他活跃引用 → 结束（文件不动，他人引用不受影响）
        Long activeRefs = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                .eq(FileRef::getFileId, fileId));
        if (activeRefs > 0) {
            return;
        }
        // Step3：无活跃引用 → del_flag=1 + delete_time=now（退出秒传判重池；
        // 物理清理交对账任务，7 天缓冲防误删不可恢复）
        fileMetaMapper.updateById(new FileMeta()
                .setId(meta.getId())
                .setDelFlag(DelFlag.DELETED)
                .setDeleteTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now()));
        log.info("文件软删完成（无活跃引用），fileId={}，operatorId={}", fileId, operatorId);
    }

    /** owner/ADMIN 口径：operatorId 与 ownerId 一致或 ADMIN，否则 NO_PERMISSION */
    private void checkOwnerOrAdmin(FileMeta meta, Long operatorId) {
        if (operatorId != null && operatorId.equals(meta.getOwnerId())) {
            return;
        }
        if (!SaRoleHelper.isAdmin()) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
    }
}
