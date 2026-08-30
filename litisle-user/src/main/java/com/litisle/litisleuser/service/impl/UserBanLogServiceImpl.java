package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.litisleuser.domain.entity.UserBanLog;
import com.litisle.litisleuser.mapper.UserBanLogMapper;
import com.litisle.litisleuser.service.UserBanLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserBanLogServiceImpl
        extends ServiceImpl<UserBanLogMapper, UserBanLog>
        implements UserBanLogService {

    @Override
    public void record(UserBanLog banLog) {
        save(banLog);
        log.info("记录用户封号/解封日志：userId={}，action={}，operatorId={}",
                banLog.getUserId(), banLog.getAction(), banLog.getOperatorId());
    }
}
