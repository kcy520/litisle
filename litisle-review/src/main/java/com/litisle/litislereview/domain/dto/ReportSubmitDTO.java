package com.litisle.litislereview.domain.dto;


import com.litisle.litislereview.enums.TargetTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 提交举报入参（POST /api/report）
 * <p>服务端校验：reporter_id != 目标 owner（防自导自演，文章/评论/私信取内容 owner、
 * 用户即 target_id、图片取 file_meta.owner_id）；同一三元组存在 status<3 举报即拒绝</p>
 */
@Data
public class ReportSubmitDTO {

    @NotNull(message = "目标类型不能为空")
    private TargetTypeEnum targetType;

    @NotNull(message = "目标ID不能为空")
    private Long targetId;

    @NotBlank(message = "举报理由不能为空")
    @Size(max = 200, message = "举报理由长度不能超过 200 个字符")
    private String reason;

    /** 证据图 fileId 列表（引用 file_meta.file_id 业务主键，可选，上限 9 张） */
    @Size(max = 9, message = "证据图最多 9 张")
    private List<String> evidenceFileIds;
}