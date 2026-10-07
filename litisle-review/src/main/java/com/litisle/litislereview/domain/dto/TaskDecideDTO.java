package com.litisle.litislereview.domain.dto;

import com.litisle.litislereview.enums.VerdictEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人审裁决入参（POST /task/{id}/decide）
 * <p>verdict=PASS → review_task.status=3-通过；REJECT → 4-驳回（含建处罚/下架联动）；
 * 高风险裁决（人审直接封禁）需 confirm=true 二次确认 + 双签留痕（moderation_record mod_type=6）</p>
 */
@Data
public class TaskDecideDTO {

    /** 1-PASS 2-REJECT（SUSPICIOUS 不允许作为终审裁决值） */
    @NotNull(message = "裁决结果不能为空")
    private VerdictEnum verdict;

    @Size(max = 500, message = "审核意见长度不能超过 500 个字符")
    private String opinion;

    /** 高风险裁决显式二次确认（缺失时拒绝） */
    private Boolean confirm;
}