package com.litisle.litisleapi.dto.moderation;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 发布链路禁言态校验结果 DTO（板块五 5-2.4，AuthClient.checkSpeak 返回载荷）
 *
 * 【使用场景】content/social（私信）发布前经此契约校验禁言态拦截发言：
 * speakAllowed=false 时调用方按 banned/muted 细分抛业务异常（如封禁提示/禁言剩余时间提示），
 * 准入判定口径收口在 auth 侧（checkSpeak），调用方禁止自行拼装判定防口径漂移
 *
 * @author litisle
 */
@Data
public class PunishSpeakCheckDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否允许发言（false=封禁或禁言生效中；判定口径唯一收口 auth 侧） */
    private Boolean speakAllowed;

    /** 快照透传：是否封禁（供调用方定制文案） */
    private Boolean banned;

    /** 快照透传：是否禁言 */
    private Boolean muted;

    /** 禁言到期时间（muted=true 且永久禁言时为 null；调用方不得据此二次判定准入） */
    private LocalDateTime frozenUntil;
}
