package com.litisle.litisleauth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.moderation.PunishAuditDTO;
import com.litisle.litisleapi.dto.moderation.PunishStatusDTO;
import com.litisle.litisleapi.feign.ReviewClient;
import com.litisle.litisleauth.constant.AuthCacheKey;
import com.litisle.litisleauth.service.PunishAuditService;
import com.litisle.litisleauth.service.PunishSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 处罚对账审计服务实现（B5，5-3.2）
 *
 * 【活跃会话判定口径】StpUtil.isLogin(userId)（Sa-Token 会话存储 Redis 直查，无请求上下文可用）
 * 与 设备索引 Set（auth:user:devices:{userId}）非空——任一为真即视为活跃：
 * 两者生命周期短暂不一致（logout 清 Set 与 Sa-Token 会话非原子），对账场景宁判活跃多补踢
 * （kickout 幂等无害），不判离线漏踢
 *
 * @author litisle
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PunishAuditServiceImpl implements PunishAuditService {

    /** 设备会话/禁言快照 Redis 读写模板 */
    private final StringRedisTemplate stringRedisTemplate;

    /** 本地禁言快照服务（读快照 / 覆盖式写入） */
    private final PunishSnapshotService punishSnapshotService;

    /** 审核服务 Feign 客户端（refreshSnapshot 回查权威态，降级 fail-fast） */
    private final ReviewClient reviewClient;

    /**
     * 查询用户处罚对账审计快照（只读无副作用）
     * <p>活跃会话判定故障（Sa-Token/Redis 异常）保守按活跃上报——宁多补踢不漏踢</p>
     */
    @Override
    public PunishAuditDTO getPunishAudit(Long userId) {
        // 步骤0：参数闸门 fail-fast——对账查询必须定位到具体用户
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤1：活跃会话判定——Sa-Token 登录态（主判定）与设备索引 Set（兑底）任一为真
        boolean hasActiveSession = isSessionActive(userId);
        // 步骤2：从redis读本地禁言快照（getSnapshot 不返回 null，无记录为全空快照）
        PunishStatusDTO snapshot = punishSnapshotService.getSnapshot(userId);
        // 步骤3：组装审计载荷（仅布尔态与时间，无 UGC）
        PunishAuditDTO dto = new PunishAuditDTO();
        dto.setUserId(userId);
        dto.setHasActiveSession(hasActiveSession);
        dto.setSnapshotBanned(snapshot.getBanned());
        dto.setSnapshotMuted(snapshot.getMuted());
        dto.setSnapshotFrozenUntil(snapshot.getFrozenUntil());
        log.info("处罚对账审计查询: userId={}, hasActiveSession={}, snapshotBanned={}, snapshotMuted={}",
                userId, hasActiveSession, dto.getSnapshotBanned(), dto.getSnapshotMuted());
        return dto;
    }

    /**
     * 反向补解封：回查 review 权威态并覆盖本地快照（SSOT 收口，禁止 sweeper 直写快照）
     *
     * 以 review 服务为 SSOT（唯一可信源），回查权威处罚状态，然后覆盖写入本地 Redis 快照。
     *  本质就是"用远端权威态纠正/同步本地缓存"。   即刷新快照
     *
     * <p>回查失败/业务失败向上抛 IllegalStateException——sweeper 侧记 warn 跳过本用户，
     * 下轮 6-1 调度重试；覆盖动作与 B1 消费端完全同构（幂等，重复执行无害）</p>
     */
    @Override
    public void refreshPunishSnapshot(Long userId) {
        // 步骤0：参数闸门 fail-fast
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 步骤1：回查 review 权威态（降级工厂 fail-fast 抛 BizException，catch 转义统一非预期口径）
        Result<PunishStatusDTO> result;
        try {
            result = reviewClient.getPunishStatus(userId);
        } catch (BizException e) {
            log.error("反向补解封回查权威态失败，userId={}", userId, e);
            throw new IllegalStateException("反向补解封回查权威态失败，userId=" + userId, e);
        }
        // 步骤2：回查结果闸门——业务失败/空载荷不允许覆盖（脏覆盖=漏封/误解封）
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new IllegalStateException("反向补解封回查失败，userId="
                    + userId + "，msg=" + (result == null ? "null" : result.getMessage()));
        }
        // 步骤3：覆盖式写入（撤销=全空快照覆盖=同步解封，与 B1 消费端同构幂等）
            //把审核服务回查到的权威处罚状态（banned/muted），序列化成 JSON 后直接 SET 覆盖写入 Redis——作为 auth 侧本地缓存
        punishSnapshotService.overwriteFromAuthority(result.getData());
        log.info("反向补解封快照刷新完成: userId={}, banned={}, muted={}",
                userId, result.getData().getBanned(), result.getData().getMuted());
    }

    /**
     * 活跃会话判定（宁多补踢不漏踢）：Sa-Token 登录态 ∥ 设备索引 Set 非空
     * <p>两者生命周期短暂不一致（logout 清 Set 与 Sa-Token 会话非原子），
     * 任一判定故障保守按活跃上报（kickout 幂等无害，多补踢优于漏踢）</p>
     */
    private boolean isSessionActive(Long userId) {
        // 判定一：Sa-Token 登录态（会话存储 Redis 直查，无请求上下文可用）
        try {
            if (Boolean.TRUE.equals(StpUtil.isLogin(userId))) {
                return true;
            }
        } catch (Exception e) {
            // Sa-Token/Redis 故障：保守按活跃上报
            log.error("Sa-Token 登录态查询失败，保守按活跃会话上报，userId={}", userId, e);
            return true;
        }
        // 判定二：设备索引 Set 非空（auth:user:devices:{userId}；kickAllDevices/logout 时删除）
        try {
            Long size = stringRedisTemplate.opsForSet().size(AuthCacheKey.USER_DEVICES + userId);
            if (size != null && size > 0) {
                return true;
            }
        } catch (Exception e) {
            // Redis 故障：同上保守上报
            log.error("设备索引查询失败，保守按活跃会话上报，userId={}", userId, e);
            return true;
        }
        return false;
    }
}
