package com.litisle.litislereview.controller.feign;


import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.moderation.PunishStatusDTO;
import com.litisle.litisleapi.dto.moderation.UsernameRejectRecordDTO;
import com.litisle.litisleapi.feign.ReviewClient;
import com.litisle.litislereview.service.PunishService;
import com.litisle.litislereview.service.UsernameRejectRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

/**
 * ReviewClient Feign 契约实现（供 auth 模块注册链路取证落库远程调用）
 *
 * <p>注意：路径、HTTP 方法由 ReviewClient 接口上的映射注解决定；
 * 网关不路由 /api/feign/** 前缀，仅服务间可用</p>
 *
 * @author litisle
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class ReviewFeignProviderController implements ReviewClient {

    /** 用户名拒绝取证落库服务 */
    private final UsernameRejectRecordService usernameRejectRecordService;

    /** 处罚链路服务（权威态快照查询提供方） */
    private final PunishService punishService;

    /**
     * 用户名注册拒绝取证落库（4-7.1 不建任务只取证）
     */
    @Override
    public Result<Void> insertUsernameRejectRecord(UsernameRejectRecordDTO dto) {
        // 日志仅落指纹（禁回显用户名明文，载荷本就不携带 PII）
        log.info("Feign 用户名拒绝取证落库: contentHash={}", dto == null ? null : dto.getContentHash());
        return usernameRejectRecordService.insertRejectRecord(dto);
    }

    /**
     * 查询用户处罚权威态（B1，5-2.2）：auth 消费端覆盖本地快照的数据源
     * <p>内部契约端点：网关不配置 /api/review/punish/status 路由，防公网直查处罚态</p>
     */
    @Override
    public Result<PunishStatusDTO> getPunishStatus(Long userId) {
        // 日志仅落 userId（处罚态属敏感信息，不落任何 UGC）
        log.info("Feign 处罚权威态查询: userId={}", userId);
        PunishStatusDTO punishStatusSnapshot = punishService.getPunishStatusSnapshot(userId);
        return Result.success(punishStatusSnapshot);
    }
}