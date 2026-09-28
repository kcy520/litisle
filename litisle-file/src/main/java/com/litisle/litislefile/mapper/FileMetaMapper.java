package com.litisle.litislefile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislefile.domain.dto.FileQuotaUsage;
import com.litisle.litislefile.domain.entity.FileMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * @Mapper + extends BaseMapper<FileMeta>（MyBatis-Plus）
 *
 * 关键查询：
 * - 秒传查重：selectOne(sha256 + size + biz_type) —— 走 idx_dedup(sha256, size, biz_type, del_flag)，
 *   条件加 del_flag=0；PRIVATE/LICENSE 追加 owner_id（按 owner 隔离去重，防秒传探测）
 * - 对账补偿：selectList(status=0 AND create_time < NOW()-30min) —— S3 head 探测后恢复/废弃
 * - 批量取 URL：selectList(in fileIds) —— Feign batchGetUrls 用，一次往返避免 N+1
 * - 清理任务：selectList(del_flag=1 AND delete_time < NOW()-7天) —— 物理删除前再经
 *   FileRefMapper 校验 file_ref 无活跃引用，仍有引用则跳过并告警（方案C，兜底不误删）
 */
@Mapper
public interface FileMetaMapper extends BaseMapper<FileMeta> {

    /**
     * 统计用户配额用量（per-user 存储配额校验用）：
     * - 口径 del_flag=0 全部计入（含 status=0 占位 → 预占语义，防上传中绕过配额）
     * - 返回 SUM(size) 与 COUNT(*)，无记录时 COALESCE 兜底 0
     * - 执行计划：走 idx_owner_biz 的 owner_id 最左前缀定位该用户的行，但 size/del_flag 不在该索引中
     *   → 需逐行回表，成本 ≈ 该用户行数 N；因 maxFileCount 封顶 N≤200，回表量有界（亚毫秒级）
     * - 【演进提示】若将来把 maxFileCount 放宽至千级以上，或本查询进入高频读路径，
     *   再把 idx_owner_biz 演进为覆盖索引 (owner_id, biz_type, del_flag, size)（索引总数不变）
     * - 【不新增同前缀索引】另加 idx_owner_quota(owner_id, ...) 会与 idx_owner_biz 形成同前缀冗余，禁止
     *
     * COALESCE(value1, value2, value3, ...)
     * 返回参数列表中第一个不为 NULL 的值 如果全部都是 NULL，就返回 NULL
     *
     * sql：解释：查询某个用户（ownerId）下，未被删除的文件总大小（usedSize）和文件总数（usedCount）
     *
     * sum(size)：对 size 字段求和
     * count(*) as usedcount 统计 符合条件的记录数 —— 也就是：文件数量
     *FileQuotaUsage：包含2个字段，其一为 usedSize，其二为 usedCount
     *  1 次查询，同时返回 size 和 count。
     */
    @Select("select coalesce(sum(size), 0) as usedsize, count(*) as usedcount " +
             "from file_meta where owner_id = #{ownerId} and del_flag = 0")
    FileQuotaUsage selectQuotaUsage(@Param("ownerId") Long ownerId);

}
