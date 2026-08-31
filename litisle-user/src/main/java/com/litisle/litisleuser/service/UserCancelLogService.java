package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleuser.domain.entity.UserCancelLog;

/**
 * 用户注销审计日志服务
 * <p>
 * 记录用户自助注销与管理员强制注销流水，保留操作时用户名/角色快照，
 * 供后台追溯与合规审计；不做逻辑删除，保留完整历史。
 *
 * @author litisle
 */
public interface UserCancelLogService extends IService<UserCancelLog> {

    /**
     * 记录单条注销日志
     *
     * @param cancelLog 已组装好的日志对象
     */
    void record(UserCancelLog cancelLog);
}
