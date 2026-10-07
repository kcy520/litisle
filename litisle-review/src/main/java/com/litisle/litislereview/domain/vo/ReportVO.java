package com.litisle.litislereview.domain.vo;


import com.litisle.litislereview.enums.AbuseFlagEnum;
import com.litisle.litislereview.enums.ReportStatusEnum;
import com.litisle.litislereview.enums.TargetTypeEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 举报视图（举报人本人 / REVIEWER-ADMIN 视角）
 * <p>reason/result 为 UGC，回显前必须 HTML sanitize + 输出编码（审核只做判定、不做净化）；
 * reporter_id 仅 REVIEWER/ADMIN 视图返回，本人视图脱敏</p>
 */
@Data
@Accessors(chain = true)
public class ReportVO {

    private Long id;
    private TargetTypeEnum targetType;
    private Long targetId;
    private String reason;
    private ReportStatusEnum status;
    private String result;
    private AbuseFlagEnum abuseFlag;
    private LocalDateTime createTime;
}