package com.litisle.litislereview.domain.vo;



import com.litisle.litislereview.enums.ModerationBizTypeEnum;
import com.litisle.litislereview.enums.PunishActionEnum;
import com.litisle.litislereview.enums.PunishStatusEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 处罚流水视图（「我的处罚」页 / 内部契约登录态判定）
 * <p>reason 为 UGC，回显前必须 HTML sanitize + 输出编码</p>
 */
@Data
@Accessors(chain = true)
public class PunishLogVO {

    private Long id;
    private PunishActionEnum action;
    private Integer score;
    private String reason;
    private ModerationBizTypeEnum bizType;
    private Long bizId;
    private LocalDateTime expireTime;
    private PunishStatusEnum status;
    private LocalDateTime createTime;
}