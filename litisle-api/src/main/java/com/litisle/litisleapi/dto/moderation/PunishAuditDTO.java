package com.litisle.litisleapi.dto.moderation;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 处罚对账审计快照 DTO（AuthClient.getPunishAudit 回查载荷，临时清单 B5，5-3.2）
 *
 * 【使用场景】双向对账 sweeper（挂 review 侧 6-1 调度）对每个 punish_log 生效封禁行回查
 * auth 侧实况，一次请求同时拿到「会话态 + 本地快照态」，支撑两个补漏方向判定：
 * - 正向补踢：banned 快照/封禁生效 但 hasActiveSession=true → 调既有 forceLogout 补 kickout
 * - 反向补解封：snapshotBanned=true 但 punish_log 无生效封禁行 → 调 refreshPunishSnapshot
 *   让 auth 以 review 回查覆盖快照（SSOT：快照只能以回查覆盖，sweeper 不直接写）
 *
 * @author litisle
 */
@Data
public class PunishAuditDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 是否存在活跃会话（Sa-Token 登录态 或 设备索引 Set 非空，任一为真即视为活跃——宁多补踢不漏踢） */
    private Boolean hasActiveSession;

    /** 本地禁言快照：是否封禁（auth:punish:snapshot:{userId}） */
    private Boolean snapshotBanned;

    /** 本地禁言快照：是否禁言 */
    private Boolean snapshotMuted;

    /** 本地禁言快照：禁言到期时间（muted=true 时有值） */
    private LocalDateTime snapshotFrozenUntil;
}
