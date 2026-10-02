package com.litisle.litislefile.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commonredis.lock.DistributedLock;
import com.litisle.litislefile.client.SeaweedFsClient;
import com.litisle.litislefile.config.FileProperties;
import com.litisle.litislefile.domain.entity.FileMeta;
import com.litisle.litislefile.domain.entity.FileRef;
import com.litisle.litislefile.enums.UploadStatusEnum;
import com.litisle.litislefile.mapper.FileMetaMapper;
import com.litisle.litislefile.mapper.FileRefMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 文件对账定时任务（每日 02:00，多实例靠 Redisson 锁互斥，仅一个实例执行）
 * <p>
 * 两步对账（见 seaweedfs笔记.md §3.4 / File-Architecture.md）：
 * Step1 补偿上传：扫 status=0（上传中）且超 30min 的占位记录 →
 *   S3 head 对象存在（put 实际成功但 status 更新丢失）→ 置 status=1 恢复；
 *   对象不存在（put 失败的幽灵记录）→ del_flag=1 + delete_time=now() 废弃
 * Step2 物理清理：扫 del_flag=1 且 delete_time 超 7 天（方案C 软删缓冲期）→
 *   跳过 quarantine/ 前缀记录（违规图保留期由 moderation 侧 quarantine.retention-days 控制，防两套保留期打架）→
 *   前置校验 file_ref 无活跃引用（仍有引用 → 跳过并告警，防悬空他人引用）→
 *   删 SeaweedFS 本体 + 缩略图派生 key（listKeys 前缀 path 查出后逐个删，S3 delete 幂等）→
 *   删 file_meta 行 + 防御性同删 file_ref 残留行
 * <p>
 * 每条记录独立 try-catch：单条失败（如存储不可用）不影响其余记录，下一轮对账自愈
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileReconcileTask {

    /** 每日 02:00 触发；可用配置 litisle.file.reconcile-task.cron 覆盖 */
    private static final String CRON_DAILY_2AM = "${litisle.file.reconcile-task.cron:0 0 2 * * ?}";

    /** 多实例互斥锁 key（RedissonLock 追加 lock: 前缀，与 file:sha: 风格统一） */
    private static final String LOCK_KEY = "file:reconcile";

    // 对账阈值参数已外置到 yml/Nacos：litisle.file.reconcile-task.compensate-threshold-minutes / physical-clean-delay-days
    // （原 COMPENSATE_THRESHOLD_MINUTES / PHYSICAL_CLEAN_DELAY_DAYS 私有常量随外置移除，改经 FileProperties.getReconcileTask() 读取）

    private final FileMetaMapper fileMetaMapper;
    private final FileRefMapper fileRefMapper;
    private final SeaweedFsClient seaweedFsClient;
    private final DistributedLock distributedLock;
    private final FileProperties fileProperties;

    /**
     * 对账入口：抢锁成功才执行（tryLock waitTime=0，抢不到说明另一实例正在跑，直接放弃本轮）
     */
    @Scheduled(cron = CRON_DAILY_2AM)
    public void reconcile() {
        // 多实例互斥：waitTime=0 表示不等待，抢不到锁说明另一实例正在跑
        if (!distributedLock.tryLock(LOCK_KEY, 0, TimeUnit.SECONDS)) {
            log.info("其他实例正在执行文件对账，本实例跳过");
            return;
        }
        try {
            // Step1：补偿上传（status=0 幽灵记录恢复/废弃）
            int compensated = compensateUploading();
            // Step2：物理清理（软删超 7 天且无活跃引用）
            int cleaned = physicallyCleanDeleted();
            log.info("文件对账完成：补偿上传 {} 条，物理清理 {} 条", compensated, cleaned);
        } finally {
            // 无论成功失败都释放锁，防下一轮死锁
            distributedLock.unlock(LOCK_KEY);
        }
    }

    /**
     * Step1 补偿上传：status=0 且超 30min 的占位记录
     */
    private int compensateUploading() {
        // 扫描占位超 30 分钟的在途记录（正常上传秒级完成，超时即为需要对账的"幽灵"）
        List<FileMeta> ghosts = fileMetaMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getStatus, UploadStatusEnum.UPLOADING)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED)
                .le(FileMeta::getCreateTime, LocalDateTime.now().minusMinutes(
                        fileProperties.getReconcileTask().getCompensateThresholdMinutes())));
        if (ghosts.isEmpty()) {
            return 0;
        }
        int count = 0;
        // 逐条处理：单条失败不影响其余（下一轮对账自愈）
        for (FileMeta meta : ghosts) {
            try {
                // S3 head 探测：对象存在说明 put 已成功（status 更新丢失），恢复 status=1
                if (seaweedFsClient.exists(meta.getBucket(), meta.getPath())) {
                    fileMetaMapper.updateById(new FileMeta()
                            .setId(meta.getId())
                            .setStatus(UploadStatusEnum.COMPLETED)
                            .setUpdateTime(LocalDateTime.now()));
                } else {
                    // 对象不存在（put 失败的幽灵记录）：软删废弃，退出秒传判重池
                    fileMetaMapper.updateById(new FileMeta()
                            .setId(meta.getId())
                            .setDelFlag(DelFlag.DELETED)
                            .setDeleteTime(LocalDateTime.now())
                            .setUpdateTime(LocalDateTime.now()));
                }
                count++;
            } catch (Exception e) {
                // 放宽为 Exception：防 NPE 等非受检异常中断整轮对账（单条失败不影响其余，下一轮自愈）
                log.error("补偿上传失败，fileId={}，下一轮重试", meta.getFileId(), e);
            }
        }
        return count;
    }

    /**
     * Step2 物理清理：del_flag=1 且 delete_time 超 7 天
     */
    private int physicallyCleanDeleted() {
        // 扫描软删超 7 天缓冲期的记录（此期间可恢复误删）
        List<FileMeta> expired = fileMetaMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getDelFlag, DelFlag.DELETED)
                .le(FileMeta::getDeleteTime, LocalDateTime.now().minusDays(
                        fileProperties.getReconcileTask().getPhysicalCleanDelayDays())));
        if (expired.isEmpty()) {
            return 0;
        }
        int count = 0;
        // 隔离跳过开关与前缀（外置 yml/Nacos：litisle.file.reconcile-task.skip-quarantine-cleanup / quarantine-prefix）
        String quarantinePrefix = fileProperties.getReconcileTask().getQuarantinePrefix();
        boolean skipQuarantine = fileProperties.getReconcileTask().isSkipQuarantineCleanup()
                && quarantinePrefix != null && !quarantinePrefix.isEmpty();
        // 逐条处理：单条失败不影响其余（下一轮对账自愈）
        for (FileMeta meta : expired) {
            try {
                // 隔离图（quarantine/ 前缀）跳过物理清理：违规图保留期由 moderation 侧
                // （litisle.moderation.quarantine.retention-days，当前=10 天）控制，
                // 防 file 侧 7 天清理提前删除违规图证据、致申诉无法复核（两套保留期打架）
                if (skipQuarantine && meta.getPath() != null && meta.getPath().startsWith(quarantinePrefix)) {
                    log.info("隔离图跳过物理清理（保留期由 moderation 侧控制），fileId={}", meta.getFileId());
                    continue;
                }
                // 前置校验：仍有活跃引用则跳过并告警（方案C：防悬空他人引用）
                Long activeRefs = fileRefMapper.selectCount(new LambdaQueryWrapper<FileRef>()
                        .eq(FileRef::getFileId, meta.getFileId()));
                if (activeRefs > 0) {
                    log.warn("文件仍有 {} 条活跃引用却已软删超期，跳过物理清理，fileId={}",
                            activeRefs, meta.getFileId());
                    continue;
                }
                // 删 SeaweedFS：前缀 path 一次查出本体 + 全部缩略图派生 key（_thumb_*）逐个删
                List<String> keys = seaweedFsClient.listKeys(meta.getBucket(), meta.getPath());
                for (String key : keys) {
                    seaweedFsClient.delete(meta.getBucket(), key);
                }
                // 删 file_meta 行（物理删除）
                fileMetaMapper.deleteById(meta.getId());
                // 防御性同删 file_ref 残留行（正常应为 0 行）
                fileRefMapper.delete(new LambdaQueryWrapper<FileRef>()
                        .eq(FileRef::getFileId, meta.getFileId()));
                count++;
            } catch (Exception e) {
                // 放宽为 Exception：防非受检异常中断整轮清理（同上，单条失败不影响其余）
                log.error("物理清理失败，fileId={}，下一轮重试", meta.getFileId(), e);
            }
        }
        return count;
    }
}
