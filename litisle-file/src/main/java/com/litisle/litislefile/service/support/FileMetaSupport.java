package com.litisle.litislefile.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litislefile.config.FileProperties;
import com.litisle.litislefile.domain.entity.FileMeta;
import com.litisle.litislefile.mapper.FileMetaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 文件元数据公共支撑组件（Service 按业务域拆分后，跨域复用的私有方法收敛于此）：
 *
 * - getMetaOrThrow：fileId 查活跃元数据（原 FileServiceImpl#getMetaOrThrow 逐字迁移，
 *   上传/访问/管理/审核联动四域共用）
 * - buildPublicUrl：PUBLIC 直链拼接（原 FileServiceImpl#buildPublicUrl 逐字迁移，
 *   上传/访问两域共用）
 */
@Component
@RequiredArgsConstructor
public class FileMetaSupport {

    private final FileMetaMapper fileMetaMapper;
    private final FileProperties fileProperties;

    /** fileId 查活跃元数据（del_flag=0），不存在抛 FILE_NOT_FOUND */
    public FileMeta getMetaOrThrow(String fileId) {
        FileMeta meta = fileMetaMapper.selectOne(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getFileId, fileId)
                .eq(FileMeta::getDelFlag, DelFlag.NOT_DELETED));
        if (meta == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND);
        }
        return meta;
    }

    /** PUBLIC 直链：publicUrlBase + bucket + path（与 SDK endpoint 分离，接 CDN 仅改配置） */
    public String buildPublicUrl(String bucket, String path) {
        return fileProperties.getSeaweedfs().getPublicUrlBase() + "/" + bucket + "/" + path;
    }
}
