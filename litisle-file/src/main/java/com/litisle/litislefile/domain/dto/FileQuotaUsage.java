package com.litisle.litislefile.domain.dto;

import lombok.Data;

/**
 * 用户配额用量聚合结果（per-user 存储配额校验用）
 * <p>由 FileMetaMapper#selectQuotaUsage 单行聚合查询返回：</p>
 * - usedSize：已用总容量（字节，SUM(size)）
 * - usedCount：已用文件数（COUNT(*)）
 * <p>口径：del_flag=0 全部计入（含 status=0 占位 → 预占语义）</p>
 */
@Data
public class FileQuotaUsage {

    /** 已用总容量（字节） */
    private Long usedSize;

    /** 已用文件数 */
    private Long usedCount;

}
