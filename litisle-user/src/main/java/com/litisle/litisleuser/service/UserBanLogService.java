package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleuser.domain.entity.UserBanLog;

/**
 * 用户封号/解封审计日志服务
 * <p>
 * 每次封号/解封（含定时任务自动解封）写入一条流水，保留操作时用户名/角色快照，
 * 供后台追溯与合规审计；不做逻辑删除，保留完整历史。
 *
 * @author litisle
 */
public interface UserBanLogService extends IService<UserBanLog> {

    /**
     * 记录单条封号/解封日志
     *
     * @param banLog 已组装好的日志对象
     */
    void record(UserBanLog banLog);
}
