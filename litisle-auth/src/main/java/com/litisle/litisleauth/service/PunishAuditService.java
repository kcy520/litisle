package com.litisle.litisleauth.service;

import com.litisle.litisleapi.dto.moderation.PunishAuditDTO;
import com.litisle.litisleapi.dto.moderation.PunishSpeakCheckDTO;

/**
 * 处罚对账审计服务（临时清单 B5，5-3.2 双向对账 sweeper 的 auth 侧数据供给）
 *
 * 【契约端点职责划分】
 * - getPunishAudit：只读审计，sweeper 据此判定补漏方向，不产生任何写副作用
 * - refreshPunishSnapshot：反向补解封动作，唯一写路径是「回查 review 权威态→覆盖本地快照」——
 *   auth 快照 SSOT 是 review 回查，sweeper 不直接写快照（防绕过权威源写入脏态）
 * - checkSpeak（5-2.4）：发布链路禁言态校验，读快照做发言准入判定（决策收口 auth，
 *   调用方禁自行拼装判定防口径漂移），只读无副作用
 * - 正向补踢不新增端点：复用既有 forceLogout（DELETE /api/feign/user/{userId}/sessions），
 *   kickout 语义与 B2 完全一致，避免同一动作双口径
 *
 * @author litisle
 */
public interface PunishAuditService {

    /**
     * 查询用户处罚对账审计快照（活跃会话态 + 本地快照态，只读无副作用）
     *
     * @param userId 用户 ID
     * @return 审计快照（非 null；无快照时布尔态全 false）
     */
    PunishAuditDTO getPunishAudit(Long userId);

    /**
     * 反向补解封：回查 review 权威态并覆盖本地快照（撤销丢失事件的最终收敛动作）
     *
     * @param userId 用户 ID
     */
    void refreshPunishSnapshot(Long userId);

    /**
     * 发布链路禁言态校验（5-2.4）：读本地快照做发言准入判定
     *
     * @param userId 发言用户 ID
     * @return 校验结果（非 null；无快照=全空态=允许发言）
     */
    PunishSpeakCheckDTO checkSpeak(Long userId);
}
