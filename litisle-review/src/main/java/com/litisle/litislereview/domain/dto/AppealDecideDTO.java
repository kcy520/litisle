package com.litisle.litislereview.domain.dto;


import com.litisle.litislereview.enums.AppealStatusEnum;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 申诉裁决入参（POST /appeal/{id}/judge）
 * <p>仅 ADMIN 可担任（REVIEWER 不可自裁）；原驳回裁决人（review_task.decided_by）回避；
 * 改判撤销「封禁/违规分清零」属高风险裁决，需 confirm=true + 双签留痕</p>
 */
@Data
public class AppealDecideDTO {

    /** 1-维持 2-撤销（只允许终态二值，不允许写 0-待裁决） */
    @NotNull(message = "裁决结果不能为空")
    private AppealStatusEnum status;

    @Size(max = 500, message = "裁决意见长度不能超过 500 个字符")
    private String judgeOpinion;

    /** 高风险裁决显式二次确认（缺失时拒绝） */
    private Boolean confirm;
}