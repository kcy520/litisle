package com.litisle.litislefile.service;

import com.litisle.litislefile.domain.vo.FileUploadVO;
import com.litisle.litislefile.enums.StorageBizTypeEnum;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件上传能力接口（由 FileService 按业务域拆分；REST 由 FileUploadController 调用）：
 *
 * FileUploadVO upload(MultipartFile file, BizTypeEnum bizType, Long ownerId)
 * List<FileUploadVO> uploadBatch(List<MultipartFile> files, BizTypeEnum bizType, Long ownerId)
 */
public interface FileUploadService {

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
}
