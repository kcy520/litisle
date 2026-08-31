package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.litisleuser.domain.entity.UserCancelLog;
import com.litisle.litisleuser.mapper.UserCancelLogMapper;
import com.litisle.litisleuser.service.UserCancelLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserCancelLogServiceImpl
        extends ServiceImpl<UserCancelLogMapper, UserCancelLog>
        implements UserCancelLogService {

    @Override
    public void record(UserCancelLog cancelLog) {
        save(cancelLog);
        log.info("记录用户注销日志：userId={}，source={}，operatorId={}",
                cancelLog.getUserId(), cancelLog.getSource(), cancelLog.getOperatorId());
    }
}
