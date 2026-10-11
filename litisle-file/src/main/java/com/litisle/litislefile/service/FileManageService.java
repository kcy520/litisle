package com.litisle.litislefile.service;

import com.litisle.litislefile.enums.StorageBizTypeEnum;

/**
 * 文件管理能力接口（由 FileService 按业务域拆分；REST 由 FileManageController 调用，
 * bindRef/unbindRef 由 FileFeignProviderController 提供服务间契约）：
 *
 * void bindRef(String fileId, BizTypeEnum bizType, Long bizId, Long ownerId)   // 业务方建立引用时登记（与业务表同事务，方案C）
 * void unbindRef(String fileId, BizTypeEnum bizType, Long bizId, Long ownerId) // 业务方解除引用时登记
 * void delete(String fileId, Long operatorId)            // 解绑调用方引用；无活跃引用才 del_flag=1（方案C，owner/ADMIN 校验）
 */
public interface FileManageService {

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
