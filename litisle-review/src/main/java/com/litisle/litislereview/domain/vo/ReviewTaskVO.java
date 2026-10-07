package com.litisle.litislereview.domain.vo;

import com.litisle.litislereview.enums.*;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 审核任务视图（人审队列/详情，REVIEWER/ADMIN 可见）
 * <p>命中词/机审证据固化展示（mod_verdict 冗余列直读免 JOIN）</p>
 */
@Data
@Accessors(chain = true)
public class ReviewTaskVO {

    private Long id;
    private ModerationBizTypeEnum bizType;
    private Long bizId;
    private ReviewSceneEnum scene;
    private Layer1HitEnum layer1Hit;
    private String hitWords;
    private Layer2RiskEnum layer2Risk;
    private ReviewTaskStatusEnum status;
    private VerdictEnum modVerdict;
    private Long assignee;
    private LocalDateTime claimTime;
    private Long decidedBy;
    private String opinion;
    private LocalDateTime decideTime;
    private LocalDateTime createTime;
}