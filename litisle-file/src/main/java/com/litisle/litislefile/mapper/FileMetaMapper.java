package com.litisle.litislefile.mapper;

/**
 * @Mapper + extends BaseMapper<FileMeta>（MyBatis-Plus）
 *
 * 关键查询：
 * - 秒传查重：selectOne(by sha256) —— 走 idx_sha256 索引，条件加 del_flag=0
 * - 批量取 URL：selectList(in fileIds) —— Feign batchGetUrls 用，一次往返避免 N+1
 * - 清理任务：selectList(del_flag=1 AND update_time < NOW()-7天)
 */
public interface FileMetaMapper {
}
