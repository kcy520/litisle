package com.litisle.litislefile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislefile.domain.entity.FileMeta;
import org.apache.ibatis.annotations.Mapper;

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
}
