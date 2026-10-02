package com.litisle.litislefile.service;

import com.litisle.litislefile.domain.dto.FileDownloadResource;
import com.litisle.litislefile.domain.vo.FileMetaVO;
import com.litisle.litislefile.domain.vo.FileUploadVO;
import com.litisle.litislefile.domain.vo.PresignVO;
import com.litisle.litislefile.domain.vo.ThumbnailVO;
import com.litisle.litislefile.enums.StorageBizTypeEnum;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文件能力接口（REST 由 FileController 调用；未来 FileFeignApi 亦复用此接口）：
 *
 * FileUploadVO upload(MultipartFile file, BizTypeEnum bizType, Long ownerId)
 * List<FileUploadVO> uploadBatch(List<MultipartFile> files, BizTypeEnum bizType, Long ownerId)
 * String getUrl(String fileId, Long operatorId)         // 统一入口：内部分流 PUBLIC→直链 / PRIVATE→预签名
 *                                                       // 调用方无需感知文件 access 属性
 *                                                       // PRIVATE 需 owner/ADMIN 校验（operatorId 取网关 X-User-Id）
 *                                                       // 【预留】审核启用后：返回前校验 safe_flag=0 则拒绝（见笔记 §3.6）
 * Map<String, String> batchGetUrls(List<String> fileIds) // Feign 批量映射，避免列表页 N+1
 * PresignVO presign(String fileId, Integer expireMinutes, Long operatorId) // 强制签发：expireMinutes ≤ 配置上限 + owner/ADMIN
 * FileMetaVO getMeta(String fileId, Long operatorId) // 非 owner/ADMIN 脱敏 ownerId
 * ThumbnailVO getThumbnail(String fileId, Integer w, Integer h)
 * void bindRef(String fileId, BizTypeEnum bizType, Long bizId, Long ownerId)   // 业务方建立引用时登记（与业务表同事务，方案C）
 * void unbindRef(String fileId, BizTypeEnum bizType, Long bizId, Long ownerId) // 业务方解除引用时登记
 * void delete(String fileId, Long operatorId)            // 解绑调用方引用；无活跃引用才 del_flag=1（方案C，owner/ADMIN 校验）
 */
public interface FileService {

    /**
     * 单文件上传（含秒传：sha256+size+biz_type 判重命中直接复用 fileId）
     *
     * @param file     上传文件
     * @param bizType  业务类型（决定 bucket/access/MIME 白名单）
     * @param ownerId  上传者ID（网关 X-User-Id）
     */
    FileUploadVO upload(MultipartFile file, StorageBizTypeEnum bizType, Long ownerId);

    /**
     * 批量上传（正文多图，上限 9 张，逐个走单文件链路）
     */
    List<FileUploadVO> uploadBatch(List<MultipartFile> files, StorageBizTypeEnum bizType, Long ownerId);

    /**
     * 获取访问 URL（统一入口：PUBLIC→直链；PRIVATE→owner/ADMIN 校验后预签名 30min）
     *
     * @param operatorId 当前操作者ID（PRIVATE 权限校验用）
     */
    String getUrl(String fileId, Long operatorId);

    /**
     * 批量获取 fileId→URL 映射（避免列表页 N+1；PUBLIC→直链，PRIVATE→预签名）
     */
    Map<String, String> batchGetUrls(List<String> fileIds);

    /**
     * 服务端流式代理下载（支持 HTTP Range 断点续传）
     * <p>默认下载走直连范式（getUrl/presign 的 URL 即凭证，客户端直连对象存储）；本方法用于
     * “必须经服务端中转”的场景（鉴权收紧/防直链/水印），服务端手动透传 Range：
     * 无 Range → 200 全量；合法 Range → 206 部分内容；非法 Range → 416（rangeInvalid=true）。
     * PRIVATE 需 owner/ADMIN 校验；PUBLIC 登录即可。</p>
     *
     * @param fileId      文件ID
     * @param operatorId  当前操作者ID（PRIVATE 权限校验用）
     * @param rangeHeader 原始 Range 头（如 "bytes=0-1023"，可为空表示全量）
     * @return 流式下载资源（持有 S3 对象流 + 响应元信息，调用方负责关闭 stream）
     */
    FileDownloadResource download(String fileId, Long operatorId, String rangeHeader);

    /**
     * 强制签发预签名 URL（owner/ADMIN；expireMinutes 空取配置默认，
     * 必须校验 ≤ 配置上限防 99999 绕过 LICENSE 限时）
     */
    PresignVO presign(String fileId, Integer expireMinutes, Long operatorId);

    /**
     * 元数据查询（mime/size/宽高/align）；非 owner/ADMIN 脱敏 ownerId
     */
    FileMetaVO getMeta(String fileId, Long operatorId);

    /**
     * 内部元数据查询（仅服务间 Feign 提供，不走登录态/脱敏）
     * <p>返回完整 ownerId 供调用方做归属校验（如 user 设置头像前校验 file_meta.owner_id）。
     * 与 getMeta 的区别：getMeta 面向外部用户需脱敏防枚举；本方法仅 /api/feign 路径可达（网关未路由），
     * 无对外暴露面
     */
    FileMetaVO getInternalMeta(String fileId);

    /**
     * 缩略图 URL（w/h 空取 yml 默认 300×300；路径派生不落库，按需生成）
     */
    ThumbnailVO getThumbnail(String fileId, Integer width, Integer height);

    /**
     * 引用登记（方案C：业务方建立引用时调用，与业务表同事务由调用方保证）
     */
    void bindRef(String fileId, StorageBizTypeEnum bizType, Long bizId, Long ownerId);

    /**
     * 解除引用登记（方案C：业务方解绑引用时调用）
     */
    void unbindRef(String fileId, StorageBizTypeEnum bizType, Long bizId, Long ownerId);

    /**
     * 删除（方案C 解绑语义）：校验 owner/ADMIN →
     * Step1 删 file_ref 当前调用方引用行 → Step2 仍有活跃引用则结束 →
     * Step3 无引用才 del_flag=1 + delete_time=now()（物理清理交对账任务，7 天缓冲）
     */
    void delete(String fileId, Long operatorId);
}
