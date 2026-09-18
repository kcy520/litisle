package com.litisle.litislefile.service;

/**
 * 文件能力接口（REST 由 FileController 调用；未来 FileFeignApi 亦复用此接口）：
 *
 * FileUploadVO upload(MultipartFile file, BizTypeEnum bizType, Long ownerId)
 * List<FileUploadVO> uploadBatch(List<MultipartFile> files, BizTypeEnum bizType, Long ownerId)
 * String getUrl(String fileId)                          // 统一入口：内部分流 PUBLIC→直链 / PRIVATE→预签名
 *                                                       // 调用方无需感知文件 access 属性
 *                                                       // 【预留】审核启用后：返回前校验 safe_flag=0 则拒绝（见笔记 §3.6）
 * Map<String, String> batchGetUrls(List<String> fileIds) // Feign 批量映射，避免列表页 N+1
 * FileMetaVO getMeta(String fileId)
 * ThumbnailVO getThumbnail(String fileId, int w, int h)
 * void delete(String fileId, Long operatorId)            // 软删除，owner/ADMIN 校验
 */
public interface FileService {
}
