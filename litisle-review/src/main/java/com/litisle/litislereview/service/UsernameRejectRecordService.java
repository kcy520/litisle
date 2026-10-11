package com.litisle.litislereview.service;


import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.moderation.UsernameRejectRecordDTO;

/**
 * 用户名拒绝取证落库服务（ReviewClient 契约提供方，4-7.1）
 *
 * @author litisle
 */
public interface UsernameRejectRecordService {

    /**
     * 用户名注册 DFA HIGH 拒绝取证落库：不建 review_task、不进 MQ，仅追加 moderation_record
     *
     * @param dto 取证载荷（contentHash + hitWords + wordlibVer）
     * @return success-落库成功；fail-载荷非法/落库失败
     */
    Result<Void> insertRejectRecord(UsernameRejectRecordDTO dto);
}