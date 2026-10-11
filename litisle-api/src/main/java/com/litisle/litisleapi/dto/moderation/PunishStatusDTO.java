package com.litisle.litisleapi.dto.moderation;


import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 处罚权威态快照 DTO（ReviewClient.getPunishStatus 回查载荷，5-2.2）
 *
 * 【使用场景】auth/user 消费 moderation.punish.result 后按 userId 回查 review 权威态，
 * 以本 DTO 覆盖本地禁言/封禁快照——「覆盖式」而非「增量式」：无论事件 op=1/2，
 * 以 review 侧现态为准，天然幂等、与到达顺序无关
 *
 * 【撤销语义】无任何生效处罚时返回全空快照（banned=false、muted=false、frozenUntil=null），
 * 消费方据此同步解封（快照源在 auth/user，撤销处罚后快照须同步解封，B3）
 *
 * @author litisle
 */
@Data
public class PunishStatusDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long userId;

    /** 是否处于生效封禁（punish_log action=2 且 status=1；true 时消费方执行 kickout 快照兜底） */
    private Boolean banned;

    /** 是否处于生效禁言（punish_log action=1 且 status=1）
     * muted : 已禁言
     * */
    private Boolean muted;

    /** 禁言到期时间（muted=true 时有值；封禁为永久态不落本字段，banned 布尔即权威） */
    private LocalDateTime frozenUntil;
}
