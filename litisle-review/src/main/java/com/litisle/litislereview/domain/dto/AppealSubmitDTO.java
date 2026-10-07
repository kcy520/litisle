package com.litisle.litislereview.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交申诉入参（POST /api/appeal）
 * <p>reportId/punishId/reviewTaskId 三者至少其一非空（应用层校验）；
 * 服务端校验 appellant_id 与内容 owner 一致（防替他人申诉）+ 双层防重（关联列级 + 内容级）</p>
 */
@Data
public class AppealSubmitDTO {

    /** 关联举报ID（举报场景） */
    private Long reportId;

    /** 关联处罚记录ID（处罚场景） */
    private Long punishId;

    /** 关联审核任务ID（机审驳回场景：无 report/punish 可关联） */
    private Long reviewTaskId;

    @NotBlank(message = "申诉内容不能为空")
    @Size(max = 500, message = "申诉内容长度不能超过 500 个字符")
    private String content;
}