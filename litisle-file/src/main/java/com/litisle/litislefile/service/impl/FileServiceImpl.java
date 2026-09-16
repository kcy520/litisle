package com.litisle.litislefile.service.impl;

/**
 * 核心实现编排（依赖 SeaweedFsClient / FileMetaMapper / FileProperties / Redisson）。
 *
 * 【上传（含秒传）】最复杂链路：
 * 1. 校验：size ≤ maxImageSize(10MB)、MIME 白名单（image/jpeg|png|webp|gif）
 * 2. 流式算 SHA256（DigestInputStream，勿全量读内存）
 * 3. 查 idx_sha256：命中 → 直接返回已有 fileId + deduplicated=true（秒传，不落 SeaweedFS）
 * 4. 未命中 → Redisson 分布式锁 biz:file:sha:{hash}
 *    锁只包「查+写」临界区；S3 put 幂等，放锁外，不必占锁
 *    拿锁后双重检查（防并发重复上传）
 * 5. 生成 fileId = UUID（无连字符，32 位十六进制），作为 S3 对象 key 的一部分
 *    path = {yyyy/MM/dd}/{fileId}.{ext}（fileId 在上传前确定，非 SeaweedFS 返回值）
 * 6. SeaweedFsClient.put(bucket, path, bytes, mime)
 *    bucket = collections 映射（avatar/cover/article/license）
 * 7. 解析图片宽高（ImageIO.read → width/height，用于前端布局预留）
 * 8. 插 file_meta：ownerId=X-User-Id；LICENSE 强制 access=PRIVATE（服务层覆盖）
 *    access 由 bizType 决定：AVATAR/COVER/ARTICLE_IMG → PUBLIC；LICENSE → PRIVATE
 *
 * 【URL 获取】统一入口 getUrl(fileId)：查 file_meta → access 分流
 *    PUBLIC→publicUrlBase + bucket + path 拼直链
 *    PRIVATE→presign + owner/ADMIN 权限校验 + bizType 二次校验（纵深防御）
 * 【删除】校验 owner/ADMIN → del_flag=1；物理清理交定时任务（7 天缓冲）
 * 【定时清理】@Scheduled 每日凌晨：扫 del_flag=1 且超 7 天 →
 *    删 SeaweedFS 本体（含缩略图派生 key）→ 物理删元数据
 */
public class FileServiceImpl {
}
